"""Execute pinned llama_chat_format.py handler/helper bodies over deterministic stub ports."""

import argparse
import ast
import base64
import contextlib
import gc
import hashlib
import json
import pathlib
import sys
import types

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=pathlib.Path, required=True)
args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for source in audit["baseline"]["files"]:
    data = (args.source_root / source["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == source["sha256"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == source["sha"]

future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)


def execute(nodes, namespace):
    exec(compile(ast.fix_missing_locations(ast.Module(body=[future] + nodes, type_ignores=[])),
               "pinned-chat", "exec"), namespace)


tree = ast.parse((args.source_root / "llama_cpp/llama_chat_format.py").read_text(encoding="utf-8"))
body = {getattr(node, "name", None): node for node in tree.body}
classes = {}
for node in tree.body:
    if isinstance(node, ast.ClassDef):
        classes[node.name] = node


def nested(owner, name):
    for node in owner.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
            return node
    raise KeyError(name)


class Handle:
    def __init__(self, name):
        self.name = name


import dataclasses

@dataclasses.dataclass
class ChatFormatterResponse:
    prompt: object = None
    stop: object = None
    added_special: bool = False
    stopping_criteria: object = None


def make_show():
    def show(value):
        if callable(value):
            return getattr(value, "__name__", "fn")
        if isinstance(value, bytes):
            return value.hex()
        if isinstance(value, Handle):
            return value.name
        if hasattr(value, "_hname"):
            return value._hname
        if dataclasses.is_dataclass(value) and not isinstance(value, type):
            return {f.name: show(getattr(value, f.name)) for f in dataclasses.fields(value)}
        if isinstance(value, (list, tuple)):
            return [show(item) for item in value]
        if isinstance(value, dict):
            return {key: show(item) for key, item in value.items()}
        return value
    return show


class Stubs:
    def __init__(self, case, trace):
        self.case = case
        self.trace = trace
        self.show = make_show()

    def recording(self, name, impl):
        def call(*values, **kwargs):
            entry = [name] + [self.show(value) for value in values]
            if kwargs:
                entry.append(self.show(kwargs))
            self.trace.append(entry)
            return impl(*values, **kwargs)
        return call

    def grammar_ns(self):
        ns = types.SimpleNamespace()
        ns.JSON_GBNF = "JSON_GBNF_TEXT"
        ns.LlamaGrammar = types.SimpleNamespace(
            from_string=self.recording(
                "from_string", lambda grammar, verbose=False: Handle("grammar")),
            from_json_schema=self.recording(
                "from_json_schema", self._from_schema))
        ns.json_schema_to_gbnf = self.recording(
            "json_schema_to_gbnf", lambda j: "gbnf:" + str(len(j)))
        return ns

    def _from_schema(self, schema, verbose=False):
        if self.case.get("schemaFail"):
            raise ValueError("bad schema")
        return Handle("schema_grammar")

    def llama_types_ns(self):
        def msg(role_key):
            def make(role, content):
                return {"role": role, "content": content}
            return make
        return types.SimpleNamespace(
            ChatCompletionRequestSystemMessage=msg("system"),
            ChatCompletionRequestAssistantMessage=msg("assistant"),
            ChatCompletionRequestMessage=dict)

    def autotokenizer(self):
        tokenizer = types.SimpleNamespace(eos_token="</s>")
        tokenizer.apply_chat_template = self.recording(
            "apply_chat_template",
            lambda messages, tokenize=False, **kw: self.case.get("prompt", "PROMPT"))
        return tokenizer

    def transformers_ns(self):
        return types.SimpleNamespace(AutoTokenizer=types.SimpleNamespace(
            from_pretrained=self.recording(
                "from_pretrained", lambda path: self.autotokenizer())))

    def jinja_env(self):
        stubs = self

        class Env:
            def __init__(self, **kwargs):
                stubs.trace.append(["env_init", sorted(kwargs)])

            def from_string(self, template):
                stubs.trace.append(["from_string", len(template)])
                return types.SimpleNamespace(
                    render=lambda **ctx: stubs._render(ctx))

        return Env

    def _render(self, ctx):
        self.trace.append(["render", self.show(ctx)])
        return self.case.get("rendered", "RENDERED")

    @contextlib.contextmanager
    def suppress(self, disable=True):
        self.trace.append(["suppress_stdout_stderr", disable])
        yield


def capture(action):
    try:
        return action()
    except Exception as error:
        return dict(error=type(error).__name__, message=str(error))


def drive(case):
    trace = []
    stubs = Stubs(case, trace)
    show = stubs.show
    ns = {"Handle": Handle, "List": list, "Dict": dict, "Optional": object,
          "Union": object, "Any": object, "Iterator": object, "Tuple": tuple,
          "Literal": object, "json": json,
          "llama_grammar": stubs.grammar_ns(),
          "llama_types": stubs.llama_types_ns(),
          "transformers": stubs.transformers_ns(),
          "ImmutableSandboxedEnvironment": stubs.jinja_env(),
          "suppress_stdout_stderr": stubs.suppress,
          "os": types.SimpleNamespace(path=types.SimpleNamespace()),
          "sys": types.SimpleNamespace(stderr=Handle("stderr")),
          "print": lambda *a, **kw: trace.append(["print", *[show(v) for v in a]]),
          "ChatFormatterResponse": ChatFormatterResponse,
          "llama": types.SimpleNamespace(verbose=case.get("verbose", False),
                                         create_completion=lambda **kw: ("completion", kw))}
    results = []
    box = {}

    tfmod = types.ModuleType("transformers")
    tfmod.AutoTokenizer = stubs.transformers_ns().AutoTokenizer
    sys.modules["transformers"] = tfmod
    wanted_funcs = ["_convert_text_completion_logprobs_to_chat",
                    "chat_formatter_to_chat_completion_handler",
                    "_grammar_for_json", "_grammar_for_json_schema",
                    "_grammar_for_response_format",
                    "_convert_completion_to_chat_function",
                    "hf_autotokenizer_to_chat_formatter",
                    "hf_autotokenizer_to_chat_completion_handler",
                    "hf_tokenizer_config_to_chat_formatter",
                    "hf_tokenizer_config_to_chat_completion_handler"]
    execute([body[name] for name in wanted_funcs], ns)

    # functionary nested helpers: exec each handler's nested defs into their own namespace
    for tag, owner_name in (("v1", "functionary_chat_handler"),
                            ("v2", "functionary_v1_v2_chat_handler")):
        owner = body[owner_name]
        sub = dict(ns)
        sub["SYSTEM_MESSAGE"] = (
            "A chat between a curious user and an artificial intelligence assistant. The assistant"
            " gives helpful, detailed, and polite answers to the user's questions. The assistant"
            " calls functions with appropriate input when necessary")
        fn_names = []
        outer = None
        for node in owner.body:
            if isinstance(node, ast.FunctionDef):
                fn_names.append(node.name)
        execute([node for node in owner.body if isinstance(node, ast.FunctionDef)], sub)
        box[tag] = sub

    # classes with static/class helpers
    execute([classes["Llava15ChatHandler"], classes["MTMDChatHandler"],
             classes["Qwen25VLChatHandler"]], ns)
    Llava15, MTMD, Qwen = ns["Llava15ChatHandler"], ns["MTMDChatHandler"], ns["Qwen25VLChatHandler"]

    def run(step):
        name = step[0]
        if name == "gJson": return show(ns["_grammar_for_json"](step[1]))
        if name == "gSchema":
            return show(ns["_grammar_for_json_schema"](*step[1:]))
        if name == "gResp":
            return show(ns["_grammar_for_response_format"](step[1], *step[2:]))
        if name == "autoFmt":
            fmt = ns["hf_autotokenizer_to_chat_formatter"](step[1])
            box["fmt"] = fmt
            return "ok"
        if name == "autoCall":
            return show(box["fmt"](step[1], **step[2]))
        if name == "autoHandler":
            return show(ns["hf_autotokenizer_to_chat_completion_handler"](step[1]))
        if name == "cfgFmt":
            fmt = ns["hf_tokenizer_config_to_chat_formatter"](step[1], *step[2:])
            box["fmt"] = fmt
            return "ok"
        if name == "cfgCall":
            return show(box["fmt"](step[1], **step[2]))
        if name == "cfgHandler":
            return show(ns["hf_tokenizer_config_to_chat_completion_handler"](*step[1:]))
        if name == "convFn":
            return show(ns["_convert_completion_to_chat_function"](*step[1:]))
        if name == "convStream":
            gen = ns["_convert_completion_to_chat_function"](step[1], iter(step[2]), True)
            return show(list(gen))
        if name == "typeDef":
            return box[step[3]]["generate_type_definition"](step[1], step[2], {})
        if name == "sharedDefs":
            return box[step[3]]["generate_shared_definitions"](step[1], step[2])
        if name == "schemaFns":
            return box[step[2]]["generate_schema_from_functions"](step[1])
        if name == "prepV1":
            return box["v1"]["prepare_messages_for_inference"](*step[1:])
        if name == "prepV2":
            tokenizer = types.SimpleNamespace(hf_tokenizer=types.SimpleNamespace(
                apply_chat_template=lambda msgs, tokenize=False: step[1]))
            return box["v2"]["prepare_messages_for_inference"](step[2], tokenizer, *step[3:])
        if name == "getGrammar":
            sub = box["v2"]
            # bind handler-scope names the body needs
            sub["functions"] = step[2]
            sub["tools"] = step[3]
            sub["llama"] = types.SimpleNamespace(verbose=case.get("verbose", False))
            return show(sub["get_grammar"](step[1]))
        if name == "imgUrls":
            return Llava15ChatHandler_get(Llava15, step[1])
        if name == "splitUrls":
            return show(Llava15.split_text_on_image_urls(step[1], step[2]))
        if name == "mtmdImgUrls":
            return MTMD.get_image_urls(step[1])
        if name == "tmplMsgs":
            handler = object.__new__(MTMD)
            return show(handler._get_template_messages(step[1], step[2]))
        if name == "convMsg":
            return show(MTMD._convert_message_for_template(step[1], step[2]))
        if name == "convPart":
            return show(MTMD._convert_content_part_for_template(step[1], step[2]))
        if name == "decPiece":
            return MTMD._decode_token_piece(bytes.fromhex(step[1]) if step[2] else step[1])
        if name == "postText":
            handler = object.__new__(MTMD)
            return handler._postprocess_template_text(step[1], step[2], step[3])
        if name == "qwen":
            handler = object.__new__(Qwen)
            handler.verbose = case.get("verbose", False)
            kwargs = dict(step[1])
            kwargs["llama"] = box["qwen_llama"]
            return handler(**kwargs)
        raise AssertionError("unknown op " + name)

    def Llava15ChatHandler_get(cls, messages):
        return cls.get_image_urls(messages)

    class FakeLlama:
        def __init__(self, trace):
            object.__setattr__(self, "trace", trace)
            object.__setattr__(self, "_nt", 0)
            self.n_tokens = 7
            self.input_ids = types.SimpleNamespace(fill=lambda v: trace.append(["fill", v]))
            self._ctx = types.SimpleNamespace(
                kv_cache_clear=lambda: trace.append(["kv_cache_clear"]))
            self.trace = trace

        _hname = "llama"

        def reset(self):
            self.trace.append(["reset"])

        @property
        def n_tokens(self):
            return self._nt

        @n_tokens.setter
        def n_tokens(self, v):
            self.trace.append(["n_tokens", v])
            self._nt = v

    if any(s[0] == "qwen" for s in case["ops"]):
        box["qwen_llama"] = FakeLlama(trace)

        def parent_call(self, **kwargs):
            trace.append(["parent_call", show(kwargs)])
            return "PARENT"

        Llava15.__call__ = parent_call

    for step in case["ops"]:
        results.append(capture(lambda s=step: run(s)))
    return dict(results=results, trace=trace)


cases = []


def add(kind, **case):
    case["kind"] = kind
    cases.append(case)


# grammar helpers
add("grammar",
    ops=[["gJson", False], ["gJson", True], ["gSchema", "{}", False, True],
         ["gSchema", "{}", False, False], ["gSchema", "{}", True, False]])
add("grammar", schemaFail=True,
    ops=[["gSchema", "{}", False, True], ["gSchema", "{}", False, False]])
add("grammar",
    ops=[["gResp", {"type": "text"}], ["gResp", {"type": "json_object"}],
         ["gResp", {"type": "json_object", "schema": {"a": 1}}]])
# hf autotokenizer formatter and handler
add("hfAuto", prompt="P<|im_end|>",
    ops=[["autoFmt", "repo/x"], ["autoCall", [{"role": "user", "content": "hi"}], {"k": 1}],
         ["autoCall", [{"role": "user", "content": "two"}], {}]])
add("hfAuto", ops=[["autoHandler", "repo/y"]])
# hf tokenizer_config formatter and handler
add("hfCfg", rendered="R<p>",
    ops=[["cfgFmt", {"chat_template": "tpl", "bos_token": "<s>", "eos_token": "</s>"}],
         ["cfgCall", [{"role": "user", "content": "hi"}], {}],
         ["cfgCall", [{"role": "user", "content": "hi"}], {"x": 1}]])
add("hfCfg",
    ops=[["cfgFmt", {"chat_template": "t", "bos_token": "<s>", "eos_token": "</s>"}, False],
         ["cfgCall", [{"role": "user", "content": "hi"}], {}]])
add("hfCfg",
    ops=[["cfgFmt", {"chat_template": "t", "bos_token": "<s>", "eos_token": "</s>"}],
         ["cfgHandler", {"chat_template": "t", "bos_token": "<s>", "eos_token": "</s>"}]])
add("hfCfg",
    ops=[["cfgFmt", {"chat_template": 1, "bos_token": "<s>", "eos_token": "</s>"}],
         ["cfgFmt", {"chat_template": "t", "eos_token": "</s>"}],
         ["cfgFmt", {"chat_template": "t", "bos_token": "<s>"}]])
# _convert_completion_to_chat_function non-stream and stream
completion = {
    "id": "cmpl-1", "created": 11, "model": "m",
    "choices": [{"index": 0, "text": "{\"a\":1}", "logprobs": None, "finish_reason": "stop"}],
    "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}}
chunks = [
    {"id": "c1", "created": 5, "model": "m",
     "choices": [{"index": 0, "text": "{\"a", "logprobs": None, "finish_reason": None}]},
    {"id": "c1", "created": 5, "model": "m",
     "choices": [{"index": 0, "text": ":1}", "logprobs": None, "finish_reason": "length"}]}]
add("convFn", ops=[["convFn", "mytool", completion, False]])
add("convFn", ops=[["convFn", "mytool", {"id": "x", "created": 1, "model": "m",
                   "choices": [{"index": 0, "text": "t", "logprobs": None}]}, False]])
add("convFn", ops=[["convStream", "mytool", chunks]])
# functionary nested helpers
add("fnV1",
    ops=[["typeDef", {"type": "array", "items": {"type": "string"}}, 1, "v1"],
         ["typeDef", {"$ref": "#/$defs/Point"}, 0, "v1"],
         ["typeDef", {"type": "object", "properties": {"x": {"type": "integer"},
                                                     "y": {"enum": ["a", "b"]}}}, 0, "v1"],
         ["typeDef", {}, 1, "v1"],
         ["sharedDefs", {"P": {"type": "object", "properties": {"x": {"type": "integer"}}},
                         "E": {"enum": ["e1", "e2"]}, "S": {"type": "string"}}, 1, "v1"],
         ["schemaFns", [{"name": "f", "description": "d",
                         "parameters": {"type": "object",
                                        "properties": {"a": {"type": "string", "description": "pa"},
                                                       "b": {"type": "integer"}},
                                        "required": ["a"]}}], "v1"]])
add("fnV2",
    ops=[["typeDef", {"type": "array", "items": {"enum": ["x"]}}, 1, "v2"],
         ["sharedDefs", {"P": {"type": "object", "properties": {}}}, 1, "v2"],
         ["schemaFns", [{"name": "g", "parameters": {"type": "object", "properties": {}}}], "v2"],
         ["schemaFns", [{"name": "h",
                         "parameters": {"$defs": {"R": {"enum": ["q"]}},
                                        "properties": {"p": {"$ref": "#/$defs/R"}},
                                        "required": ["p"]}}], "v2"]])
add("prepV1",
    ops=[["prepV1",
          [{"role": "user", "content": "hi"},
           {"role": "assistant", "content": "ok"},
           {"role": "function", "name": "fn", "content": "r"},
           {"role": "assistant", "content": None,
            "function_call": {"name": "fn", "arguments": "{}"}},
           {"role": "tool", "tool_call_id": "t1", "content": "o"},
           {"role": "tool", "tool_call_id": "t2", "content": None},
           {"role": "user", "content": None},
           {"role": "assistant", "content": None, "tool_calls": [{"id": "c", "function": {"arguments": "{}"}}]},
           {"role": "assistant", "content": "c",
            "function_call": {"name": "f2", "arguments": "a"}}],
          [{"name": "fn", "parameters": {"type": "object", "properties": {}}}],
          [{"type": "function", "function": {"name": "tf", "parameters": {}}}]],
         ["prepV1", [{"role": "unknown", "content": "?"}], None, None],
         ["prepV1", [{"role": "user", "content": "u"}], None, None]])
add("prepV2",
    ops=[["prepV2", "T",
          [{"role": "user", "content": "hi"}],
          "v1",
          [{"name": "fn", "parameters": {"type": "object", "properties": {}}}],
          None, "auto"],
         ["prepV2", "T", [{"role": "user", "content": "hi"}],
          "v2", None,
          [{"type": "function", "function": {"name": "tf", "parameters": {}}}],
          "none"],
         ["prepV2", "T", [],
          "v2", None, None, "none"]])
add("grammar2", verbose=False,
    ops=[["getGrammar", "fn", [{"name": "fn", "parameters": {"type": "object"}}], None]])
add("grammar2", verbose=True,
    ops=[["getGrammar", "tf", None,
          [{"type": "function", "function": {"name": "tf", "parameters": None}}]]])
add("grammar2", verbose=True, schemaFail=True,
    ops=[["getGrammar", "fn", [{"name": "fn", "parameters": {}}], None]])
# image url helpers
add("media",
    ops=[["imgUrls", [{"role": "user", "content": [
            {"type": "text", "text": "t"},
            {"type": "image_url", "image_url": {"url": "u1"}},
            {"type": "image_url", "image_url": "u2"}]},
           {"role": "user", "content": None},
           {"role": "assistant", "content": [{"type": "image_url", "image_url": "u3"}]},
           {"role": "user", "content": [{"type": "image_url", "image_url": "u4"}]}]],
         ["splitUrls", "a u1 b u2", ["u1", "u2"]],
         ["splitUrls", "x u2 y u1", ["u1", "u2"]],
         ["splitUrls", "plain", ["u1"]],
         ["mtmdImgUrls", [{"role": "user", "content": [
             {"type": "image_url", "image_url": {"url": "m1"}}]}]],
         ["tmplMsgs", [{"role": "user", "content": [
             {"type": "image_url", "image_url": "u"}, {"type": "text", "text": "t"}]},
            {"role": "system", "content": "s"}], "<m>"],
         ["convMsg", {"role": "user", "content": [
             {"type": "image_url", "image_url": "u"}, "plain"]}, "<m>"],
         ["convPart", {"type": "image_url", "image_url": "u"}, "<m>"],
         ["convPart", {"type": "text", "text": "t"}, "<m>"],
         ["decPiece", "616263", True], ["decPiece", 7, False],
         ["postText", "x u1 y u2", ["u1", "u2"], "<m>"]])
# Qwen25VL __call__ preamble
add("qwen", verbose=True,
    ops=[["qwen", {"llama": "L", "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": "u"}]}]}]])

gc.disable()
rows = []
for index, case in enumerate(cases):
    try:
        gc.collect()
        rows.append("\t".join([case["kind"], base64.b64encode(wire(case)).decode(),
                               base64.b64encode(wire(drive(case))).decode()]))
    except Exception:
        print("case", index, case["kind"], file=sys.stderr)
        raise
fixture = root / "src/test/resources/python-0.3.36-chathandlers-contracts.tsv"
fixture.write_text("\n".join(rows) + "\n", encoding="ascii")
functions = [
    "_grammar_for_json", "_grammar_for_json_schema", "_grammar_for_response_format",
    "hf_autotokenizer_to_chat_formatter",
    "hf_autotokenizer_to_chat_formatter.format_autotokenizer",
    "hf_autotokenizer_to_chat_completion_handler",
    "hf_tokenizer_config_to_chat_formatter",
    "hf_tokenizer_config_to_chat_formatter.format_tokenizer_config",
    "hf_tokenizer_config_to_chat_completion_handler",
    "_convert_completion_to_chat_function",
    "_convert_completion_to_chat_function._stream_response_to_function_stream",
    "functionary_chat_handler.generate_type_definition",
    "functionary_chat_handler.generate_shared_definitions",
    "functionary_chat_handler.generate_schema_from_functions",
    "functionary_chat_handler.prepare_messages_for_inference",
    "functionary_chat_handler.prepare_messages_for_inference.message_to_str",
    "functionary_v1_v2_chat_handler.generate_type_definition",
    "functionary_v1_v2_chat_handler.generate_shared_definitions",
    "functionary_v1_v2_chat_handler.generate_schema_from_functions",
    "functionary_v1_v2_chat_handler.prepare_messages_for_inference",
    "functionary_v1_v2_chat_handler.get_grammar",
    "Llava15ChatHandler.get_image_urls",
    "Llava15ChatHandler.split_text_on_image_urls",
    "Llava15ChatHandler.split_text_on_image_urls.find_first",
    "MTMDChatHandler.get_image_urls",
    "MTMDChatHandler._get_template_messages",
    "MTMDChatHandler._convert_message_for_template",
    "MTMDChatHandler._convert_content_part_for_template",
    "MTMDChatHandler._decode_token_piece",
    "MTMDChatHandler._postprocess_template_text",
    "Qwen25VLChatHandler.__call__",
]
metadata = dict(pythonPackage="0.3.36", sourceCommit=audit["baseline"]["commit"],
                fixtureRuntime=sys.version, caseCount=len(rows), uniqueFunctions=len(functions),
                functions=functions,
                fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                method="Complete hash-verified llama_chat_format handler/helper bodies executed over deterministic grammar/tokenizer/jinja/llama stubs; string and dict outputs compared byte for byte.")
(root / "docs/python-chathandlers-contracts.json").write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(metadata, ensure_ascii=False, indent=2))
