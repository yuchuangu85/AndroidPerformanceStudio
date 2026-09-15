import { describe, expect, it } from 'vitest';
import { graftComposeInspection } from './compose-tree-merge.js';
import type { ComposeInspectionFrame } from './compose-inspection.js';
import type { LayoutSnapshot } from './snapshot.js';
import type { ViewNode } from './model.js';
const view=(id:string,children:ViewNode[]=[]):ViewNode=>({type:'view',id,className:'android.view.View',bounds:{left:0,top:0,right:10,bottom:10},visible:true,alpha:1,children,attributes:{rawProperties:{}}});
const frame:ComposeInspectionFrame={frameId:'f',generation:1,mode:'FULL',capabilities:[],details:new Map(),coverage:[],completeness:'COMPLETE',truncations:[],roots:[{viewId:10,viewsToSkip:[12],nodes:[{id:1,anchorHash:1,name:'Column',bounds:{left:0,top:0,right:10,bottom:10},systemCreated:false,flags:[],children:[{id:2,anchorHash:2,name:'AndroidView',bounds:{left:0,top:0,right:5,bottom:5},hostedViewId:11,systemCreated:false,flags:[],children:[]}]}]}]};
const host=view('view:10',[view('view:11'),view('view:12'),view('view:13')]); const snapshot:LayoutSnapshot={protocolVersion:{major:1,minor:0},packageName:'pkg',capturedAtEpochMillis:1,display:{widthPx:10,heightPx:10,density:1},capabilities:{viewHierarchy:true,composeSemantics:false,screenshots:false,timeline:false},root:host,windows:[{id:'w',title:'w',type:'OTHER',bounds:host.bounds,root:host}],defaultWindowId:'w'};
describe('graftComposeInspection',()=>it('removes skipped views and reparents hosted Android views',()=>{const merged=graftComposeInspection(snapshot,frame);const root=merged.root;if(root.type!=='view')throw new Error('view');expect(root.children.map(x=>x.id)).toEqual(['view:13','compose:10:1']);const compose=root.children[1];if(compose?.type!=='compose')throw new Error('compose');expect(compose.children[0]?.children.map(x=>x.id)).toEqual(['view:11']);expect(merged.capabilities.composeSemantics).toBe(true)}));


describe('Compose observation projection', () => {
  it('projects Compose observation counters onto merged UI nodes', () => {
    const counted: ComposeInspectionFrame = {
      ...frame,
      roots: [{
        ...frame.roots[0]!,
        nodes: [{ ...frame.roots[0]!.nodes[0]!, recomposeCount: 7, skipCount: 3 }],
      }],
    };
    const merged = graftComposeInspection(snapshot, counted);
    const compose = merged.root.children.find((node) => node.type === 'compose');
    expect(compose).toMatchObject({ type: 'compose', recomposeCount: 7, skipCount: 3 });
  });
});
