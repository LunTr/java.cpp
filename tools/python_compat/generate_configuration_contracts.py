"""Execute complete original config-state and from_pretrained orchestration bodies offline."""
import argparse,ast,base64,builtins,fnmatch,hashlib,json,os,pathlib,sys,types
from contract_wire import wire
root=pathlib.Path(__file__).resolve().parents[2];p=argparse.ArgumentParser();p.add_argument("--source-root",type=pathlib.Path,required=True);args=p.parse_args()
audit=json.loads((root/"docs/python-api-audit.json").read_text(encoding="utf-8"))
for f in audit["baseline"]["files"]:assert hashlib.sha256((args.source_root/f["path"]).read_bytes()).hexdigest()==f["sha256"]
tree=ast.parse((args.source_root/"llama_cpp/llama.py").read_text(encoding="utf-8"));node=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=="Llama")
node.body=[n for n in node.body if isinstance(n,ast.FunctionDef) and n.name in {"__getstate__","__setstate__","from_pretrained"}]
native=types.SimpleNamespace(LLAMA_LOAD_MODE_MMAP=1,LLAMA_LOAD_MODE_MMAP_MLOCK=3,LLAMA_LOAD_MODE_MLOCK=2,LLAMA_FLASH_ATTN_TYPE_ENABLED=1)
ns=dict(__name__="llama_cpp.llama",llama_cpp=native,Path=pathlib.Path,fnmatch=fnmatch,json=json,os=os,__builtins__=dict(vars(builtins)))
future=ast.ImportFrom(module="__future__",names=[ast.alias(name="annotations")],level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future,node],type_ignores=[])),"pinned-config","exec"),ns)
Llama=ns["Llama"];rows=[];counts={}
def capture(fn):
    try:return fn()
    except Exception as e:return dict(error=type(e).__name__,message=str(e))
def run(arg):
    trace=[]
    if arg["kind"]=="config":
        obj=Llama.__new__(Llama)
        for name,value in arg["values"].items():setattr(obj,name,value)
        obj.model_params=types.SimpleNamespace(**arg["model"]);obj.context_params=types.SimpleNamespace(**arg["context"])
        obj.__init__=lambda **options:trace.append(["initialize",options])
        result=capture(obj.__getstate__)
        restored=capture(lambda:obj.__setstate__(arg["restore"]))
        return dict(result=result,restored=restored,trace=trace)
    def validate(repo):trace.append(["validate",repo])
    class Files:
        def ls(self,repo,recursive):trace.append(["files",repo]);return arg["files"]
    def download(**kwargs):trace.append(["download",kwargs.copy()]);return "cached.gguf"
    def importing(name,*args,**kwargs):
        if name=="huggingface_hub":
            if not arg["installed"]:raise ImportError("missing")
            return types.SimpleNamespace(hf_hub_download=download,HfFileSystem=Files)
        if name=="huggingface_hub.utils":return types.SimpleNamespace(validate_repo_id=validate)
        return builtins.__import__(name,*args,**kwargs)
    Llama.from_pretrained.__func__.__builtins__["__import__"]=importing
    def init(self,**kwargs):self.arguments=kwargs;trace.append(["construct",kwargs])
    Llama.__init__=init
    result=capture(lambda:Llama.from_pretrained(**arg["options"]).arguments)
    return dict(result=result,trace=trace)
def record(arg):
    rows.append("\t".join(base64.b64encode(wire(value)).decode() for value in [arg,run(arg)]));counts[arg["kind"]]=counts.get(arg["kind"],0)+1
state=next(n for n in node.body if n.name=="__getstate__");fields=next(n for n in ast.walk(state) if isinstance(n,ast.Call)).keywords
values={};model={};context={}
for item in fields:
    for n in ast.walk(item.value):
        if isinstance(n,ast.Attribute) and isinstance(n.value,ast.Name) and n.value.id=="self" and n.attr not in {"model_params","context_params"}:values[n.attr]=None
        if isinstance(n,ast.Attribute) and isinstance(n.value,ast.Attribute) and n.value.value.id=="self":
            (model if n.value.attr=="model_params" else context)[n.attr]=0
values.update(model_path="model.gguf",_seed=17,_logits_all=True,n_batch=4,last_n_tokens_size=-1,verbose=False,tensor_split=[0.3,0.7],kv_overrides={"key":True})
for load in [0,1,2,3,4]:
    for flash in [-1,0,1]:record(dict(kind="config",values=values,model=dict(model,load_mode=load),context=dict(context,flash_attn_type=flash),restore={"model_path":"other","seed":0}))
files=["owner/repo/sub/model.gguf","owner/repo/extra/data.bin"]
for filename in ["*.gguf","sub/*.gguf","MODEL.GGUF","none","*",None,"[a-z]*.gguf"]:
    for local in [None,"local"]:
        for additional in [None,["*.bin"],["missing"],["*"]]:
            record(dict(kind="repository",installed=True,files=files,options=dict(repo_id="owner/repo",filename=filename,local_dir=local,additional_files=additional,n_ctx=0)))
record(dict(kind="repository",installed=False,files=[],options=dict(repo_id="owner/repo",filename="*")))
record(dict(kind="repository",installed=True,files=[{"name":x} for x in files],options=dict(repo_id="owner/repo",filename="*.gguf")))
record(dict(kind="repository",installed=True,files=files,options=dict(repo_id="owner/repo",filename="*.gguf",model_path="duplicate")))
fixture=root/"src/test/resources/python-0.3.36-configuration-contracts.tsv";fixture.write_text("\n".join(rows)+"\n",encoding="ascii")
meta=dict(sourceCommit=audit["baseline"]["commit"],fixtureRuntime=sys.version,caseCount=len(rows),casesByOperation=counts,fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),method="Complete original __getstate__/__setstate__/from_pretrained functions with tracing constructor and repository ports; no network downloads.")
(root/"docs/python-configuration-contracts.json").write_text(json.dumps(meta,indent=2)+"\n",encoding="utf-8");print(json.dumps(meta,indent=2))
