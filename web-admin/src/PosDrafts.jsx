import React,{useEffect,useState,useRef} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function PosDrafts({api,refreshKey,onCancelled}){
 const apiRef=useRef(api);apiRef.current=api;
 const [data,setData]=useState(null),[search,setSearch]=useState(''),[booking,setBooking]=useState(''),[page,setPage]=useState(0),[reload,setReload]=useState(0);
 const [loading,setLoading]=useState(false),[error,setError]=useState(''),[target,setTarget]=useState(null),[saving,setSaving]=useState(false),[cancelError,setCancelError]=useState('');
 const valid=!booking||uuid.test(booking);
 useEffect(()=>{
  let active=true;setError('');setData(null);
  if(!valid){setLoading(false);return;}
  setLoading(true);
  const params=new URLSearchParams({page,size:20,search});if(booking)params.set('bookingId',booking);
  apiRef.current('/pos/drafts?'+params).then(result=>{if(active){if(page>0&&!result.content.length&&result.totalElements>0)setPage(Math.max(0,result.totalPages-1));else setData(result);}})
   .catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[search,booking,page,reload,refreshKey,valid]);
 async function cancel(){
  setSaving(true);setCancelError('');
  try{await api('/pos/sales/'+target.id+'/cancel','POST');const id=target.id;setTarget(null);setReload(v=>v+1);await onCancelled(id);}
  catch(e){setCancelError(e.message);setReload(v=>v+1)}finally{setSaving(false)}
 }
 return <section className="content-panel" style={{marginTop:24}} aria-label="Đơn POS chờ kết toán">
  <div className="toolbar"><h3>Đơn POS chờ kết toán</h3><button className="secondary" disabled={loading||saving} onClick={()=>setReload(v=>v+1)}>Làm mới đơn nháp</button></div>
  <div className="toolbar"><label className="field">Tìm đơn nháp<input aria-label="Tìm đơn nháp" maxLength={255} placeholder="UUID hóa đơn, booking hoặc tên khách" value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}}/></label><label className="field">Lọc theo UUID booking<input aria-label="Lọc theo UUID booking" value={booking} onChange={e=>{setBooking(e.target.value.trim());setPage(0)}}/></label></div>
  {!valid?<p role="alert">Nhập đầy đủ UUID booking hợp lệ hoặc để trống.</p>:loading?<p role="status">Đang tải đơn nháp…</p>:error?<p role="alert" className="alert error">{error} <button onClick={()=>setReload(v=>v+1)}>Thử lại</button></p>:data&&!data.content.length?<p className="empty">Không có đơn POS nháp phù hợp.</p>:data&&<>
   <div className="table-scroll"><table><thead><tr><th>Hóa đơn / Booking</th><th>Khách / Người tạo</th><th>Thời điểm</th><th>Tiền hàng sau giảm</th><th>Thao tác</th></tr></thead><tbody>{data.content.map(d=><tr key={d.id}><td><code>{d.id}</code><small>{d.bookingId||'Không gắn booking'}</small></td><td>{d.customerName}<small>{d.createdByName||'Chưa có người tạo'}</small></td><td>{d.createdAt?new Date(d.createdAt).toLocaleString('vi-VN'):'—'}</td><td>{money(d.totalAmount)}<details><summary>Xem mặt hàng</summary>{d.items.map((item,i)=><p key={i}>{item.productName} · {item.quantity} × {money(item.unitPrice)} · Giảm {money(item.discountAmount)} · Còn {money(item.lineAmount)}</p>)}</details></td><td><button className="secondary" disabled={saving} onClick={()=>{setTarget(d);setCancelError('')}}>Hủy đơn nháp</button></td></tr>)}</tbody></table></div>
   <div className="toolbar"><button className="secondary" disabled={page===0||saving} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>Trang {page+1}/{Math.max(1,data.totalPages)} · {data.totalElements} đơn nháp</span><button className="secondary" disabled={page+1>=data.totalPages||saving} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
  </>}
  {target&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="cancel-draft-title"><h2 id="cancel-draft-title">Hủy đơn POS nháp?</h2><p>Hóa đơn <code>{target.id}</code></p><p>{target.customerName} · {money(target.totalAmount)}</p>{target.items.map((item,i)=><p key={i}>{item.productName} × {item.quantity}</p>)}<p>Hàng đã trừ sẽ được hoàn kho theo chứng từ.</p>{cancelError&&<p role="alert" className="alert error">{cancelError}</p>}<div className="dialog-foot"><button className="secondary" disabled={saving} onClick={()=>setTarget(null)}>Đóng</button><button className="primary" disabled={saving} onClick={cancel}>{saving?'Đang hủy…':'Xác nhận hủy đơn nháp'}</button></div></section></div>}
 </section>;
}
