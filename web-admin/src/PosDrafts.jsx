import {InlinePanel,MainSubArea,rowProps} from './MainSub.jsx';
import React,{useEffect,useState,useRef} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function PosDrafts({api,refreshKey,onCancelled}){
 const apiRef=useRef(api);apiRef.current=api;
 const [view,setView]=useState(null);
 const [data,setData]=useState(null),[search,setSearch]=useState(''),[booking,setBooking]=useState(''),[page,setPage]=useState(0),[reload,setReload]=useState(0);
 const [loading,setLoading]=useState(false),[error,setError]=useState(''),[target,setTarget]=useState(null),[saving,setSaving]=useState(false),[cancelError,setCancelError]=useState('');
 const valid=!booking||uuid.test(booking);
 useEffect(()=>{
  let active=true;setError('');setData(null);
  if(!valid){setLoading(false);return;}
  setLoading(true);
  const params=new URLSearchParams({page,size:20,search});if(booking)params.set('bookingId',booking);
  apiRef.current('/pos/drafts?'+params).then(result=>{
   const norm=Array.isArray(result)?{content:result,page:0,totalPages:1,totalElements:result.length}:result;
   if(active){if(page>0&&!norm?.content?.length&&norm?.totalElements>0)setPage(Math.max(0,(norm.totalPages||1)-1));else setData(norm);}
  })
   .catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[search,booking,page,reload,refreshKey,valid]);
 async function cancel(){
  setSaving(true);setCancelError('');
  try{await api('/pos/sales/'+target.id+'/cancel','POST');const id=target.id;setTarget(null);setReload(v=>v+1);await onCancelled(id);}
  catch(e){setCancelError(e.message);setReload(v=>v+1)}finally{setSaving(false)}
 }
 return <div className="pos-drafts-page" style={{marginTop:24}}>
  <div className="toolbar"><h3>Đơn POS chờ kết toán</h3><button className="secondary" disabled={loading||saving} onClick={()=>setReload(v=>v+1)}>Làm mới đơn nháp</button></div>
  <div className="toolbar"><label className="field">Tìm đơn nháp<input aria-label="Tìm đơn nháp" maxLength={255} placeholder="UUID hóa đơn, booking hoặc tên khách" value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}}/></label><label className="field">Lọc theo UUID booking<input aria-label="Lọc theo UUID booking" value={booking} onChange={e=>{setBooking(e.target.value.trim());setPage(0)}}/></label></div>
  <MainSubArea>
   <section className="content-panel" aria-label="Đơn POS chờ kết toán">
    {!valid?<p role="alert">Nhập đầy đủ UUID booking hợp lệ hoặc để trống.</p>:loading?<p role="status">Đang tải đơn nháp…</p>:error?<p role="alert" className="alert error">{error} <button onClick={()=>setReload(v=>v+1)}>Thử lại</button></p>:data&&!data.content?.length?<p className="empty">Không có đơn POS nháp phù hợp.</p>:data&&data.content&&<>
     <div className="table-scroll"><table><thead><tr><th>Mã đơn</th><th>Khách hàng</th><th>Người tạo</th><th>Thời điểm</th><th>Tiền hàng</th><th>Thao tác</th></tr></thead><tbody>{data.content.map(d=><tr key={d.id} {...rowProps(view?.id===d.id,()=>setView(d))}><td title={d.id+' | Booking: '+(d.bookingId||'—')}><code>{d.id.slice(0,8)}</code>{d.bookingId&&<><br/><small title={d.bookingId}><code>{d.bookingId.slice(0,8)}</code></small></>}</td><td>{d.customerName||'—'}</td><td>{d.createdByName||<span className="muted">—</span>}</td><td>{d.createdAt?new Date(d.createdAt).toLocaleString('vi-VN'):'—'}</td><td><strong>{money(d.totalAmount)}</strong></td><td><button className="secondary" disabled={saving} onClick={()=>{setTarget(d);setCancelError('')}}>Hủy đơn nháp</button></td></tr>)}</tbody></table></div>
     <div className="toolbar"><button className="secondary" disabled={page===0||saving} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>Trang {page+1}/{Math.max(1,data.totalPages)} · {data.totalElements} đơn nháp</span><button className="secondary" disabled={page+1>=data.totalPages||saving} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
    </>}
   </section>
   {view&&<InlinePanel onClose={()=>setView(null)} valid={!data||data.content.some(d=>d.id===view.id)} panelKey={view.id}><section className="dialog" role="region" aria-label="Chi tiết đơn POS nháp"><div className="dialog-head"><h2>Đơn POS #{view.id.slice(0,8)}</h2><button aria-label="Đóng chi tiết đơn nháp" onClick={()=>setView(null)}>X</button></div><p>Khách hàng: <strong>{view.customerName||'Khách vãng lai'}</strong></p><p>Tổng tiền: <strong>{money(view.totalAmount)}</strong></p><div className="table-scroll"><table><thead><tr><th>Mặt hàng</th><th>SL</th><th>Thành tiền</th></tr></thead><tbody>{view.items.map((i,n)=><tr key={n}><td>{i.productName}</td><td>{i.quantity}</td><td>{money(i.lineAmount)}</td></tr>)}</tbody></table></div></section></InlinePanel>}
  </MainSubArea>
  {target&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="cancel-draft-title"><h2 id="cancel-draft-title">Hủy đơn POS nháp?</h2><p>Hóa đơn <code>{target.id}</code></p><p>{target.customerName} · {money(target.totalAmount)}</p>{target.items.map((item,i)=><p key={i}>{item.productName} × {item.quantity}</p>)}<p>Hàng đã trừ sẽ được hoàn kho theo chứng từ.</p>{cancelError&&<p role="alert" className="alert error">{cancelError}</p>}<div className="dialog-foot"><button className="secondary" disabled={saving} onClick={()=>setTarget(null)}>Hủy</button><button className="primary" disabled={saving} onClick={cancel}>{saving?'Đang hủy…':'Xác nhận hủy đơn nháp'}</button></div></section></div>}
 </div>;
}