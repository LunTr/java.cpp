"""Execute the complete pinned Jinja2ChatFormatter class and write ignored renderer fixtures."""
import argparse,ast,base64,dataclasses,datetime,hashlib,json,pathlib,sys,types
import jinja2,jinja2.ext
from jinja2.ext import Extension
from jinja2.sandbox import ImmutableSandboxedEnvironment
from contract_wire import wire
root=pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument("--source-root",type=pathlib.Path,required=True);args=parser.parse_args()
audit=json.loads((root/"docs/python-api-audit.json").read_text(encoding="utf-8"))
for row in audit["baseline"]["files"]:assert hashlib.sha256((args.source_root/row["path"]).read_bytes()).hexdigest()==row["sha256"]
tree=ast.parse((args.source_root/"llama_cpp/llama_chat_format.py").read_text(encoding="utf-8"))
nodes=[n for n in tree.body if isinstance(n,ast.ClassDef) and n.name in {"ChatFormatterResponse","Jinja2ChatFormatter"}]
ns=dict(__name__="__main__",dataclasses=dataclasses,jinja2=jinja2,Extension=Extension,ImmutableSandboxedEnvironment=ImmutableSandboxedEnvironment,
        json=json,datetime=datetime.datetime,ChatFormatter=object,llama=types.SimpleNamespace(StoppingCriteriaList=list))
future=ast.ImportFrom(module="__future__",names=[ast.alias(name="annotations")],level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future]+nodes,type_ignores=[])),"pinned-chat-jinja","exec"),ns)
templates=[
    "{{ bos_token }}{% for message in messages %}{{ message.role }}:{{ message.content }}{{ eos_token }}{% endfor %}{% if add_generation_prompt %}assistant:{% endif %}",
    "before\n  {% for x in messages %}\n{{loop.index0}}={{x['content']}}\n  {% endfor %}\nafter\n",
    "{% set ns=namespace(count=0) %}{% for x in messages %}{% set ns.count=ns.count+1 %}{% endfor %}{{ns.count}}",
    "{% macro show(x) %}<{{x}}>{% endmacro %}{% for x in messages %}{{show(x.content)}}{% endfor %}",
    "{% for x in messages %}{% if loop.index0==0 %}{% continue %}{% endif %}{{x.content}}{% break %}{% endfor %}",
    "prefix{% generation %}{{messages|length}}{% endgeneration %}suffix",
    "{{ tools|tojson }}", "{{tools|tojson(ensure_ascii=true,sort_keys=true,indent=2)}}",
    "{{tools|tojson(separators=[',',':'])}}", "{{messages|map(attribute='content')|join('|')}}",
    "{{ missing|default('fallback') }}", "{{ missing }}", "{{missing.foo}}", "{{messages[0].content}}",
    "{{raise_exception('message from template')}}", "{% unknown %}", "{% if true %}unfinished",
    "{% raw %}{{not_rendered}}{% endraw %}", "{{ '<&>' }}", "{{ messages[0].content.strip() if messages else 'empty' }}",
]
model=root/"gguf/Qwen3.5-0.8B-Q8_0.gguf"
if model.exists():
    sys.path.insert(0,str(root/"llama.cpp/gguf-py"))
    from gguf import GGUFReader
    reader=GGUFReader(model)
    field=reader.fields.get("tokenizer.chat_template")
    if field:templates.append(field.contents())
rows=[]
messages=[[],[{"role":"user","content":"hello\0中文🙂"}],[{"role":"system","content":"rules"},{"role":"user","content":" one "},{"role":"assistant","content":"two"},{"role":"user","content":"three"}]]
for template in templates:
    for inputs in messages:
        bindings=dict(messages=inputs,tools={"é":[True,None,1.5,"<&>\0"]})
        argument=dict(template=template,bindings=bindings)
        try:
            formatter=ns["Jinja2ChatFormatter"](template,"<eos>","<bos>")
            result=dict(output=formatter(**bindings).prompt)
        except Exception as error:result=dict(error=type(error).__name__,message=str(error))
        rows.append("\t".join(base64.b64encode(wire(value)).decode() for value in [argument,result]))
fixture=root/"src/test/resources/python-0.3.36-jinja-contracts.tsv";fixture.write_text("\n".join(rows)+"\n",encoding="ascii")
metadata=dict(sourceCommit=audit["baseline"]["commit"],fixtureRuntime=sys.version,jinjaVersion=jinja2.__version__,caseCount=len(rows),
    templateDigests=[hashlib.sha256(t.encode()).hexdigest() for t in templates],fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
    method="Complete hash-verified original Jinja2ChatFormatter class; test templates and local model metadata template; no model weights or template contents in metadata.")
(root/"docs/python-jinja-contracts.json").write_text(json.dumps(metadata,indent=2)+"\n",encoding="utf-8");print(json.dumps(metadata,indent=2))
