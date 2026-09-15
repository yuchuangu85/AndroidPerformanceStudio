import { describe, expect, it } from 'vitest';
import { decodeComposeInspectionFrame, encodeGetAllParametersCommand, encodeGetComposablesCommand } from './compose-inspector-protocol.js';
const v=(x:number):number[]=>{const a:number[]=[];do{const b=x&127;x>>>=7;a.push(x?b|128:b)}while(x);return a};
const c=(p:Uint8Array[])=>Uint8Array.from(p.flatMap(x=>[...x])); const f=(n:number,x:Uint8Array)=>Uint8Array.from([...v(n<<3|2),...v(x.length),...x]); const i=(n:number,x:number)=>Uint8Array.from([...v(n<<3),...v(x)]); const s=(x:string)=>new TextEncoder().encode(x);
const zig=(x:number)=>x<0?(-x*2-1):x*2;
describe('Compose inspector protocol adapter',()=>{
 it('decodes a Kotlin-compatible tree, source, flags, bounds and absent-detail coverage',()=>{
  const stringEntry=(id:number,value:string)=>f(1,c([i(1,id),f(2,s(value))]));
  const bounds=f(1,c([i(1,10),i(2,20),i(3,30),i(4,40)]));
  const child=c([i(1,zig(2)),i(7,1),f(8,bounds),i(13,zig(8))]);
  const parent=c([i(1,zig(1)),f(2,child),i(3,9),i(4,2),i(5,42),i(6,7),i(7,3),f(8,bounds),i(9,127),i(10,17),i(11,5),i(12,3),i(13,zig(11))]);
  const root=c([i(1,100),f(2,parent),i(3,200)]);
  const tree=c([stringEntry(1,'Content'),stringEntry(2,'Screen.kt'),stringEntry(3,'System'),f(2,root)]);
  const frame=decodeComposeInspectionFrame(f(1,tree),'frame',4);
  const node=frame.roots[0]?.nodes[0]; if(!node) throw new Error('missing node');
  expect(node).toMatchObject({id:1,name:'System',systemCreated:true,hostedViewId:17,recomposeCount:5,skipCount:3,source:{fileName:'Screen.kt',lineNumber:42,offset:7},bounds:{left:10,top:20,right:40,bottom:60}});
  expect(node.flags).toHaveLength(7); expect(node.children[0]).toMatchObject({id:2,name:'Content',anchorHash:8});
  expect(frame.coverage).toHaveLength(8); expect(frame.capabilities.find(x=>x.capability==='PARAMETERS')?.availability).toBe('NOT_REQUESTED');
 });
 it('encodes exact command oneofs and generation',()=>{expect([...encodeGetComposablesCommand(7n,true)]).toEqual([0x0a,0x06,0x08,0x07,0x18,0x00,0x20,0x01]);expect([...encodeGetComposablesCommand(7n,false,9)]).toEqual([0x0a,0x06,0x08,0x07,0x18,0x09,0x20,0x00]);expect([...encodeGetAllParametersCommand(7n)]).toEqual([0x1a,0x08,0x08,0x07,0x18,0x02,0x20,0x05,0x28,0x00]);});
});

it('decodes Kotlin parameter groups including floats, resources, lambdas and packed references', () => {
 const fixed32=(n:number,x:number)=>{const b=new Uint8Array(5);b[0]=(n<<3)|5;new DataView(b.buffer).setFloat32(1,x,true);return b};
 const fixed64=(n:number,x:number)=>{const b=new Uint8Array(9);b[0]=(n<<3)|1;new DataView(b.buffer).setFloat64(1,x,true);return b};
 const entry=(id:number,value:string)=>f(1,c([i(1,id),f(2,s(value))]));
 const pentry=(id:number,value:string)=>f(2,c([i(1,id),f(2,s(value))]));
 const tree=c([entry(1,'Root'),f(2,c([i(1,1),f(2,c([i(1,2),i(7,1)]))]))]);
 const ref=f(4,c([i(1,zig(2)),i(2,3),f(3,Uint8Array.from([...v(4),...v(5)])),i(4,2),i(5,zig(7))]));
 const stringParameter=(name:number,value:number)=>c([i(1,1),i(2,name),i(11,value),ref]);
 const floatParameter=c([i(1,4),i(2,4),fixed32(14,1.5)]);
 const doubleParameter=c([i(1,3),i(2,5),fixed64(13,2.25)]);
 const resource=c([i(1,6),i(2,7),i(3,8)]);
 const resourceParameter=c([i(1,8),i(2,9),f(15,resource)]);
 const lambda=c([i(2,10),i(5,41)]);
 const lambdaParameter=c([i(1,12),i(2,11),f(16,lambda)]);
 const group=c([i(1,zig(2)),f(3,stringParameter(2,3)),f(3,stringParameter(12,3)),f(3,floatParameter),f(3,doubleParameter),f(3,resourceParameter),f(3,lambdaParameter)]);
 const params=c([pentry(2,'title'),pentry(3,'Hello'),pentry(4,'ratio'),pentry(5,'double'),pentry(6,'android'),pentry(7,'id'),pentry(8,'thing'),pentry(9,'res'),pentry(10,'Lambda.kt'),pentry(11,'callback'),pentry(12,'Modifier'),f(3,group)]);
 const frame=decodeComposeInspectionFrame(c([f(1,tree),f(3,params)]),'frame',1,true);
 const detail=frame.details.get(2); if(!detail)throw new Error('missing detail');
 expect(detail.parameters.map(x=>x.name)).toEqual(['title','ratio','double','res','callback']);
 expect(detail.modifiers.map(x=>x.name)).toEqual(['Modifier']);
 expect(detail.parameters[0]).toMatchObject({value:'Hello',truncated:true,reference:{composableId:2,compositeIndex:[4,5],kind:'MERGED_SEMANTICS',anchorHash:7}});
 expect(detail.parameters.slice(1).map(x=>x.value)).toEqual(['1.5','2.25','android:id:thing','Lambda.kt:41']);
});
