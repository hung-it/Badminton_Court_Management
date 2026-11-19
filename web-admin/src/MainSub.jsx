import React,{createContext,useContext,useLayoutEffect,useEffect,useRef,useState,useCallback} from 'react';
import {createPortal} from 'react-dom';
const Context=createContext(null);
export function rowProps(selected,action){return {'aria-selected':selected,tabIndex:0,onClick:e=>{if(!e.target.closest('button,input,select,textarea,a,label,summary,details'))action()},onKeyDown:e=>{if(e.target===e.currentTarget&&(e.key==='Enter'||e.key===' ')){e.preventDefault();action()}}}}
export function MainSub({children,navigationKey}){
 const [active,setActive]=useState(null),[target,setTarget]=useState(null),current=useRef(null);
 const register=useCallback(panel=>{const old=current.current;if(old&&old.id!==panel.id)old.close('replace');current.current=panel;setActive(panel);return()=>{if(current.current?.id===panel.id){current.current=null;setActive(null)}}},[]);
 useEffect(()=>{const panel=current.current;if(panel){panel.close();current.current=null;setActive(null)}},[navigationKey]);
 useEffect(()=>{
  const prior=new WeakMap(),remember=e=>{if(e.target.matches?.('input,select,textarea'))prior.set(e.target,{value:e.target.value,checked:e.target.checked})};
  const block=e=>{const old=prior.get(e.target);if(['input','change'].includes(e.type)){if(old){e.target.value=old.value;if(e.target.type==='checkbox')e.target.checked=old.checked}else if('defaultValue' in e.target)e.target.value=e.target.defaultValue}e.preventDefault();e.stopImmediatePropagation()};
  const guard=e=>{if(e.type==='keydown'&&!['Enter',' '].includes(e.key))return;const panel=current.current;if(!panel||e.target.closest('.ms-sub'))return;
   const action=e.target.closest('tbody tr,.ms-main button,.sidebar button,.topbar button,[data-sub-trigger],[role=tab]');
   if(!action&&!['input','change'].includes(e.type))return;
   if(panel.busy()){block(e);return}
   if(panel.dirty()&&!window.confirm('Bạn có thay đổi chưa lưu. Bỏ thay đổi để tiếp tục?')){block(e);return}
   panel.clean();remember(e);
  };
  document.addEventListener('focusin',remember,true);document.addEventListener('keydown',guard,true);document.addEventListener('click',guard,true);document.addEventListener('change',guard,true);document.addEventListener('input',guard,true);
  const pop=e=>{if(current.current?.dirty()&&!window.confirm('Bạn có thay đổi chưa lưu. Bỏ thay đổi?')){history.replaceState(null,'','/?tab='+navigationKey);e.stopImmediatePropagation()}else current.current?.clean()};window.addEventListener('popstate',pop,true);
  const unload=e=>{if(current.current?.dirty()){e.preventDefault();e.returnValue=''}};window.addEventListener('beforeunload',unload);
  return()=>{document.removeEventListener('focusin',remember,true);document.removeEventListener('keydown',guard,true);document.removeEventListener('click',guard,true);document.removeEventListener('change',guard,true);document.removeEventListener('input',guard,true);window.removeEventListener('beforeunload',unload);window.removeEventListener('popstate',pop,true)};
 },[navigationKey]);
 return <Context.Provider value={{active,target,setTarget,register}}>{children}</Context.Provider>
}
export function MainSubArea({children,className=''}){
 const c=useContext(Context);
 return <div className={'ms-layout'+(c?.active?' ms-open':'')+(className?' '+className:'')}><div className="ms-main">{children}</div><aside className="ms-sub" aria-label="Thông tin bản ghi" hidden={!c?.active} ref={c?.setTarget}/></div>;
}
export function MainSubContent({children,top,className=''}){
 const c=useContext(Context);
 return <div className={'workspace'+(className?' '+className:'')}>{top&&<div className="ms-top">{top}</div>}<div className={'ms-layout'+(c?.active?' ms-open':'')}><div className="ms-main">{children}</div><aside className="ms-sub" aria-label="Thông tin bản ghi" hidden={!c?.active} ref={c?.setTarget}/></div></div>;
}
export function InlinePanel({children,onClose,busy=false,valid=true,panelKey,changed=false}){
 const c=useContext(Context),id=useRef(Symbol()),close=useRef(onClose),dirty=useRef(false),pending=useRef(busy),external=useRef(changed);close.current=onClose;pending.current=busy;external.current=changed;
 const isDirty=()=>dirty.current||external.current,clean=()=>{dirty.current=false;external.current=false};
 useLayoutEffect(()=>c?.register({id:id.current,close:reason=>close.current?.(reason),dirty:isDirty,clean,busy:()=>pending.current}),[c?.register]);
 useEffect(()=>{dirty.current=false},[panelKey]);
 useEffect(()=>{if(!valid&&!pending.current){if(!isDirty()||window.confirm('Bản ghi không còn trong kết quả. Bỏ thay đổi chưa lưu?'))close.current?.()}},[valid,busy,panelKey]);
 const dismiss=e=>{const b=e.target.closest('button');if(!b)return;const text=b.textContent.trim();if(['X','✕','Đóng','Hủy'].includes(text)||b.getAttribute('aria-label')?.startsWith('Đóng')){if(pending.current||(isDirty()&&!window.confirm('Bạn có thay đổi chưa lưu. Bỏ thay đổi?'))){e.preventDefault();e.stopPropagation()}else clean()}};
 if(!c?.target||c.active?.id!==id.current)return null;
 return createPortal(<div className="ms-panel" onChangeCapture={e=>{if(e.target.closest('form'))dirty.current=true}} onClickCapture={dismiss}><button className="ms-close secondary" aria-label="Đóng panel" disabled={busy} onClick={()=>{if(!isDirty()||window.confirm('Bạn có thay đổi chưa lưu. Bỏ thay đổi?'))close.current?.()}}>X</button>{children}</div>,c.target);
}
