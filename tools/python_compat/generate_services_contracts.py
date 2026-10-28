"""Run complete original sampling, embedding and request-forwarding bodies with tracing ports."""
import argparse, ast, base64, builtins, ctypes, hashlib, json, pathlib, sys, types
import numpy as np
from contract_wire import wire

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(); parser.add_argument("--source-root", type=pathlib.Path, required=True); args = parser.parse_args()
audit = json.loads((root / "docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data = (args.source_root / item["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"]
    assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == item["sha"]
tree = ast.parse((args.source_root / "llama_cpp/llama.py").read_text(encoding="utf-8"))
llama = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Llama")
names = {"_init_sampler", "embed", "create_embedding", "create_completion", "__call__", "create_chat_completion", "create_chat_completion_openai_v1"}
llama.body = [n for n in llama.body if isinstance(n, ast.FunctionDef) and n.name in names]
native, internals, chat = types.SimpleNamespace(), types.SimpleNamespace(), types.SimpleNamespace()
namespace = {"np": np, "llama_cpp": native, "internals": internals, "llama_chat_format": chat, "__builtins__": dict(vars(builtins))}
namespace["ctypes"]=ctypes
class TokenData(ctypes.Structure):_fields_=[("id",ctypes.c_int), ("logit",ctypes.c_float), ("p",ctypes.c_float)]
native.llama_token_data=TokenData
future = ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future,llama],type_ignores=[])), "pinned-llama-services", "exec"), namespace)
Llama = namespace["Llama"]
internals.normalize_embedding = lambda values: values if np.linalg.norm(values) == 0 else [v / float(np.linalg.norm(values)) for v in values]
rows, counts = [], {}

def capture(fn):
    try: return fn()
    except Exception as error: return {"error": type(error).__name__, "message": str(error)}
def plain(value):
    if isinstance(value,np.generic): return value.item()
    if isinstance(value,dict): return {k:plain(v) for k,v in value.items()}
    if isinstance(value,(list,tuple)): return [plain(v) for v in value]
    return value

def run(arg):
    trace=[]; obj=Llama.__new__(Llama)
    obj.verbose=arg.get("verbose",False); obj.model_path="model.gguf"
    if arg["kind"] == "sampling":
        obj._n_vocab=4; obj.last_n_tokens_size=arg["last"]; obj._seed=int(arg["seed"]); obj._model=object()
        class FakeSampler:
            def __init__(self): trace.append(["new"]);self.custom=None
            def __getattr__(self,name):
                def add(*args,**kwargs):
                    operation=name.removeprefix("add_")
                    values=list(kwargs.values()) if kwargs else list(args)
                    if operation=="custom":self.custom=args[0];values=["callback"]
                    if operation=="grammar": values=values[1:]
                    if operation=="dist": values[0]=str(values[0])
                    if operation=="mirostat": values[1]=str(values[1])
                    if operation=="mirostat_v2": values[0]=str(values[0])
                    trace.append([operation, values])
                    if operation==arg.get("fail"): raise RuntimeError("failed "+operation)
                return add
        internals.LlamaSampler=FakeSampler
        options=arg["options"].copy()
        if arg.get("processors") is not None:
            obj._input_ids=np.array([1,2],dtype=np.intc)
            def processor(kind):
                def apply(ids,scores):
                    trace.append(["processor",kind,ids.tolist(),scores.tolist()])
                    if kind=="mutate":ids[0]=5;return scores+1
                    if kind=="scalar":return np.array([7],dtype=np.single)
                    if kind=="none":return None
                    if kind=="wrong":return np.ones(5,dtype=np.single)
                    return scores*2
                return apply
            options["logits_processor"]=[None if kind=="null" else processor(kind) for kind in arg["processors"]]
        def build():
            chain=obj._init_sampler(**options)
            if arg.get("processors") is None:return True
            data=(TokenData*4)(*[TokenData(i,i+1,0) for i in range(4)])
            status=capture(lambda:chain.custom(types.SimpleNamespace(contents=types.SimpleNamespace(size=4,data=ctypes.cast(data,ctypes.POINTER(TokenData))))))
            return dict(status=status,scores=[row.logit for row in data],ids=obj._input_ids.tolist())
        result=capture(build)
    elif arg["kind"] == "embedding":
        batch=[]; live=[]
        obj.n_embd=lambda: (trace.append(["dimensions"]) or 3)
        obj.n_batch=arg["batch"]; obj.context_params=types.SimpleNamespace(n_seq_max=arg["sequences"],embeddings=arg["enabled"])
        obj.pooling_type=lambda: (trace.append(["pooling"]) or arg["pooling"])
        def reset(): trace.append(["reset"])
        obj.reset=reset
        def tokenize(text): trace.append(["tokenize",text.hex()]); return list(text)
        obj.tokenize=tokenize
        def resetBatch(): trace.append(["resetBatch"]); batch.clear()
        def add(tokens,seq,all): trace.append(["add",tokens,seq,all]); batch.append(list(tokens))
        def clear(): trace.append(["clear"])
        def decode(ignored):
            trace.append(["decode"]); live[:]=[i+0.25 for i in range(sum(map(len,batch))*3)]
            if arg.get("fail")=="decode": raise RuntimeError("decode failed")
        obj._batch=types.SimpleNamespace(reset=resetBatch,add_sequence=add)
        obj._ctx=types.SimpleNamespace(ctx=object(),kv_cache_clear=clear,decode=decode)
        native.LLAMA_POOLING_TYPE_NONE=0
        native.llama_get_embeddings=lambda ctx: (trace.append(["embeddings"]) or live[:])
        native.llama_get_embeddings_seq=lambda ctx,i: (trace.append(["sequence",i]) or [-float(i),2.0+i,0.0])
        native.llama_perf_context_reset=lambda ctx: trace.append(["resetTimings"])
        native.llama_perf_context_print=lambda ctx: trace.append(["printTimings"])
        result=capture(lambda: (obj.create_embedding if arg["response"] else obj.embed)(**arg["options"]))
    else:
        def completion(**kwargs):
            trace.append(["completion",kwargs])
            def iterator():
                trace.append(["advance"])
                if arg.get("fail")=="advance": raise RuntimeError("advance failed")
                if not arg.get("empty",False): yield {"answer":1}
                trace.append(["advance"]); yield {"answer":2}
            return iter(()) if arg.get("empty") else iterator()
        obj._create_completion=completion
        def handler(name):
            def invoke(**kwargs):
                trace.append([name,{k:v for k,v in kwargs.items() if k!="llama"}])
                if arg.get("fail")=="handler": raise ImportError("inner import")
                return iter([{"answer":1},{"answer":2}]) if kwargs.get("stream") else {"answer":1}
            return invoke
        obj.chat_format="format"; obj.chat_handler=handler("explicit") if arg.get("handler")=="explicit" else None
        obj._chat_handlers={"format":handler("model")} if arg.get("handler")=="model" else {}
        def lookup(name): trace.append(["lookup",name]); return handler("global")
        chat.get_chat_completion_handler=lookup
        def convert(name,**kwargs):
            trace.append([name,kwargs])
            if arg.get("fail")==name: raise ImportError("convert import")
            return {"converted":kwargs}
        def importing(name,*args,**kwargs):
            if name=="openai.types.chat":
                if not arg.get("installed",True): raise ImportError("missing")
                return types.SimpleNamespace(ChatCompletion=lambda **kw:convert("convert",**kw),ChatCompletionChunk=lambda **kw:convert("chunk",**kw))
            return builtins.__import__(name,*args,**kwargs)
        namespace["__builtins__"]=dict(vars(builtins),__import__=importing)
        # Functions keep the globals' original builtins mapping: update that dictionary in place.
        for name in ["create_chat_completion_openai_v1"]: getattr(Llama,name).__builtins__["__import__"]=importing
        method={"completion":"create_completion","call":"__call__","chat":"create_chat_completion","openai":"create_chat_completion_openai_v1"}[arg["kind"]]
        def execute():
            result=getattr(obj,method)(**arg["options"])
            if hasattr(result,"__next__"):
                events=[]
                for _ in range(3):
                    def advance():
                        try:return next(result)
                        except StopIteration:return None
                    events.append(capture(advance))
                return events
            return result
        result=capture(execute)
    return {"result":result,"trace":trace}

def record(arg):
    result=run(arg); counts[arg["kind"]]=counts.get(arg["kind"],0)+1
    rows.append("\t".join(base64.b64encode(wire(plain(value))).decode() for value in [arg,result]))

for temperature in [-1,0,0.8,float("nan")]:
    for mirostat in [0,1,2,3]:
        for grammar in [None,"grammar"]:
            record(dict(kind="sampling",last=-1,seed="1267650600228229401496703205376",options=dict(temp=temperature,mirostat_mode=mirostat,grammar=grammar)))
for operation in ["penalties","top_k","typical","top_p","min_p","temp","dist"]:
    record(dict(kind="sampling",last=0,seed="0",fail=operation,options={}))
record(dict(kind="sampling",last=0,seed="0",options={"unknown":1}))
for processors in [[],["double"],["mutate","double"],["scalar"],["none"],["wrong"],["double","wrong"],["null"]]:
    record(dict(kind="sampling",last=0,seed="0",options={},processors=processors))
for pooling in [0,1]:
    for batch in [2,5]:
        for sequence in [1,2]:
            for normalize in [False,True]:
                for text in ["abc",["a","bc","d"],[],"",["abcdef"],None,"\ud800"]:
                    record(dict(kind="embedding",batch=batch,sequences=sequence,enabled=True,pooling=pooling,response=False,verbose=True,
                                options=dict(input=text,normalize=normalize,return_count=True)))
for enabled in [False,True]:
    for truncate in [False,True]:
        record(dict(kind="embedding",batch=2,sequences=1,enabled=enabled,pooling=1,response=False,options=dict(input="abcd",truncate=truncate)))
for value in ["a",["a","b"],[]]:
    record(dict(kind="embedding",batch=5,sequences=2,enabled=True,pooling=1,response=True,options=dict(input=value,model="named")))
for kind in ["completion","call"]:
    for stream in [False,True,1,None]:
        for maximum in [None,0,16]:
            record(dict(kind=kind,options=dict(prompt="prompt",stream=stream,max_tokens=maximum)))
    for fail in ["advance",""]:record(dict(kind=kind,options=dict(prompt="prompt"),fail=fail,empty=not fail))
    record(dict(kind=kind,options={}));record(dict(kind=kind,options=dict(prompt="p",unknown=1)))
for kind in ["chat","openai"]:
    for handler in ["explicit","model","global"]:
        for stream in [False,True,1,None]:
            record(dict(kind=kind,handler=handler,options=dict(messages=[],stream=stream)))
for installed in [False,True]:
    for fail in ["handler","convert","chunk"]:
        for stream in [False,True]:record(dict(kind="openai",installed=installed,fail=fail,options=dict(messages=[],stream=stream)))

fixture=root/"src/test/resources/python-0.3.36-services-contracts.tsv";fixture.write_text("\n".join(rows)+"\n",encoding="ascii")
metadata=dict(sourceCommit=audit["baseline"]["commit"],fixtureRuntime=sys.version,numpyVersion=np.__version__,caseCount=len(rows),casesByOperation=counts,
              fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),method="Execute complete pinned sampler selection, embedding and forwarding functions; trace native/service ports and errors. Native inference checked separately.")
(root/"docs/python-services-contracts.json").write_text(json.dumps(metadata,indent=2)+"\n",encoding="utf-8");print(json.dumps(metadata,indent=2))
