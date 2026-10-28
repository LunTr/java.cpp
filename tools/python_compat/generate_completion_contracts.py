"""Execute the complete pinned completion and generator bodies with deterministic model ports."""
import argparse,ast,base64,contextlib,ctypes,hashlib,io,json,pathlib,random,sys,types,warnings
import numpy as np
from contract_wire import wire

root=pathlib.Path(__file__).resolve().parents[2];parser=argparse.ArgumentParser();parser.add_argument("--source-root",type=pathlib.Path,required=True);args=parser.parse_args()
audit=json.loads((root/"docs/python-api-audit.json").read_text(encoding="utf-8"))
for item in audit["baseline"]["files"]:
    data=(args.source_root/item["path"]).read_bytes();assert hashlib.sha256(data).hexdigest()==item["sha256"]
tree=ast.parse((args.source_root/"llama_cpp/llama.py").read_text(encoding="utf-8"));node=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=="Llama")
names={"_create_completion","create_completion","_input_ids","_scores","reset","eval","generate","sample","set_seed","logits_to_logprobs"}
node.body=[n for n in node.body if isinstance(n,ast.FunctionDef) and n.name in names]
processor=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=="LogitsProcessorList")
native=types.SimpleNamespace();namespace=dict(np=np,ctypes=ctypes,llama_cpp=native,random=random,sys=sys,uuid=types.SimpleNamespace(uuid4=lambda:"fixed"),time=types.SimpleNamespace(time=lambda:1234))
namespace.update(List=__import__("typing").List,LogitsProcessor=__import__("typing").Any,warnings=warnings)
future=ast.ImportFrom(module="__future__",names=[ast.alias(name="annotations")],level=0)
exec(compile(ast.fix_missing_locations(ast.Module(body=[future,node,processor],type_ignores=[])),"pinned-completion", "exec"),namespace)
Llama=namespace["Llama"];rows=[]
def plain(v):
    if isinstance(v,np.generic):return v.item()
    if isinstance(v,dict):return {k:plain(x) for k,x in v.items()}
    if isinstance(v,(list,tuple)):return [plain(x) for x in v]
    return v
def capture(fn):
    try:return fn()
    except Exception as error:return dict(error=type(error).__name__,message=str(error))
def run(arg):
    trace=[];obj=Llama.__new__(Llama);obj.verbose=arg["verbose"];obj.model_path="model.gguf"
    obj._n_ctx=arg["capacity"];obj.n_tokens=0;obj.n_batch=4;obj._n_vocab=260;obj._seed=int(arg["initialSeed"]);obj._logits_all=arg["all"];obj._requires_eval=True
    obj.input_ids=np.zeros(arg["capacity"],dtype=np.intc);obj.scores=np.zeros((arg["capacity"],260),dtype=np.single)
    obj._is_recurrent=obj._is_hybrid=False;obj.cache=None;obj.draft_model=None;obj._sampler=None;obj.spm_infill=arg["infill"]
    obj.token_bos=lambda:0;obj.token_eos=lambda:257
    obj.metadata={"tokenizer.ggml.add_space_prefix":"true" if arg["space"] else "false"}
    obj._model=types.SimpleNamespace(token_cls=lambda:arg["cls"],token_sep=lambda:arg["sep"],add_bos_token=lambda:arg["bos"],add_eos_token=lambda:arg["eos"],token_get_text=lambda t:"<s>",vocab=object())
    def tokenize(text,add_bos=True,special=False):
        trace.append(["tokenize",text.hex(),add_bos,special]);return ([0] if add_bos else [])+[b+1 for b in text]
    def detokenize(tokens,prev_tokens=None,special=False):
        if arg.get("traceDetok"):trace.append(["detokenize",list(tokens),None if prev_tokens is None else list(prev_tokens)])
        return bytes(t-1 for t in tokens if 1<=t<=256)
    obj.tokenize,obj.detokenize=tokenize,detokenize
    current=[];counter=0
    def remove(seq,start,end):trace.append(["remove",start]);return True
    def setBatch(batch,n_past,logits_all):current[:]=batch;trace.append(["decode",list(batch),n_past])
    def logits():
        values=[t%7-3+col*0.03 for t in current for col in range(260)];obj._live=(ctypes.c_float*len(values))(*values);return obj._live
    def sample(ctx,index):
        nonlocal counter
        trace.append(["sample",index]);token=arg["sampled"][counter%len(arg["sampled"])];counter+=1
        processors=obj._sampler.processors
        if processors is not None:
            values=np.array([current[index]%7-3+col*0.03 for col in range(260)],dtype=np.single)
            for process in processors:values[:]=process(obj._input_ids,values)
        return token
    def sampler(**kwargs):trace.append(["sampler",str(obj._seed)]);return types.SimpleNamespace(sample=sample,processors=kwargs.get("logits_processor"))
    obj._init_sampler=sampler
    obj._ctx=types.SimpleNamespace(ctx=object(),kv_cache_seq_rm=remove,decode=lambda batch:None,get_logits=logits,reset_timings=lambda:trace.append(["resetTimings"]),print_timings=lambda:trace.append(["printTimings"]))
    obj.ctx=obj._ctx.ctx;obj._batch=types.SimpleNamespace(set_batch=setBatch)
    native.llama_vocab_is_eog=lambda vocab,token:token==257
    native.llama_n_ctx=lambda ctx:arg["capacity"]
    options=arg["options"].copy()
    if arg.get("stopAfter") is not None:
        def stop(ids,scores):trace.append(["stop",len(ids)]);return len(ids)>=arg["stopAfter"]
        options["stopping_criteria"]=stop
    chunks=[]
    with warnings.catch_warnings(record=True) as emitted,contextlib.redirect_stderr(io.StringIO()):
        warnings.simplefilter("always")
        def execute():
            value=obj.create_completion(**options)
            if hasattr(value,"__next__"):
                while True:
                    try:chunks.append(next(value))
                    except StopIteration:break
                return chunks
            return value
        result=capture(execute)
    return dict(result=result,chunks=chunks,trace=trace,seed=str(obj._seed),cursor=obj.n_tokens,warnings=[[type(w.message).__name__,str(w.message)] for w in emitted])

base=dict(capacity=40,initialSeed="123",all=True,bos=True,eos=False,cls=-1,sep=-1,space=True,infill=False,verbose=False,sampled=[98,99,100,257])
arguments=[]
for stream in [False,True]:
    for prompt in ["hello","",[0,0,98],[98,99],"中文🙂"]:
        for stop in [[],"b",["c","b"],["ab"],None]:arguments.append(dict(base,options=dict(prompt=prompt,stream=stream,max_tokens=3,stop=stop)))
    for maximum in [None,0,1,2,4]:arguments.append(dict(base,options=dict(prompt="p",stream=stream,max_tokens=maximum)))
    for suffix in [None,"","end",3]:
        for infill in [False,True]:arguments.append(dict(base,infill=infill,options=dict(prompt="p",stream=stream,max_tokens=2,suffix=suffix)))
    for echo in [False,True]:
        for logprobs in [0,1,3,-1]:arguments.append(dict(base,options=dict(prompt="p",stream=stream,max_tokens=2,echo=echo,logprobs=logprobs)))
    for sampled in [[228,185,174,257],[241,160,154,131,257],[256,98,257],[257],[98,99,100]]:
        arguments.append(dict(base,sampled=sampled,options=dict(prompt="p",stream=stream,max_tokens=3)))
    for seed in [None,0,42,2**40]:arguments.append(dict(base,options=dict(prompt="p",stream=stream,max_tokens=1,seed=seed)))
    for bias in [{"1":2.0},{"-1":1.0},{"999":1.0}]:arguments.append(dict(base,options=dict(prompt="p",stream=stream,max_tokens=1,logit_bias=bias)))
    for after in [1,3,10]:arguments.append(dict(base,stopAfter=after,options=dict(prompt="p",stream=stream,max_tokens=3)))
    arguments.append(dict(base,all=False,options=dict(prompt="p",stream=stream,logprobs=1)))
    arguments.append(dict(base,capacity=2,options=dict(prompt="long",stream=stream)))
    arguments.append(dict(base,verbose=True,options=dict(prompt="p",stream=stream,max_tokens=1)))
for seed in ["0","1","-1",str(2**100+7)]:arguments.append(dict(base,initialSeed=seed,options=dict(prompt="p",max_tokens=1)))
for stream in [False,True]:
    for logprobs in [None,0,2]:arguments.append(dict(base,traceDetok=True,options=dict(prompt="p",stream=stream,max_tokens=2,logprobs=logprobs)))
    arguments.append(dict(base,options=dict(prompt=[98],stream=stream,max_tokens=1,echo=True)))
for arg in arguments:
    result=run(arg);rows.append("\t".join(base64.b64encode(wire(plain(value))).decode() for value in [arg,result]))
fixture=root/"src/test/resources/python-0.3.36-completion-contracts.tsv";fixture.write_text("\n".join(rows)+"\n",encoding="ascii")
metadata=dict(sourceCommit=audit["baseline"]["commit"],fixtureRuntime=sys.version,numpyVersion=np.__version__,caseCount=len(rows),fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),method="Execute full original completion and generation functions with deterministic byte tokenizer, decode and sampling ports; preserve stream outputs before errors and warning contents.")
(root/"docs/python-completion-contracts.json").write_text(json.dumps(metadata,indent=2)+"\n",encoding="utf-8");print(json.dumps(metadata,indent=2))
