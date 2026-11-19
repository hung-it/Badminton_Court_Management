import {InlinePanel,MainSubArea,rowProps} from './MainSub.jsx';
import React,{useState,useEffect,useRef} from 'react';
export default function Accounts({api,currentUserId,onViewInvoices}){
 const detailRequest=useRef(0);
 const [fieldErrors,setFieldErrors]=useState({});
 const roleLabels={ADMIN:'Quản lý',STAFF:'Thu ngân',CUSTOMER:'Khách hàng'};
 const [role,setRole]=useState('');
 const [lockTarget,setLockTarget]=useState(null),[notice,setNotice]=useState('');
 const [data,setData]=useState(null),[search,setSearch]=useState(''),[page,setPage]=useState(0),[sort,setSort]=useState('created'),[refresh,setRefresh]=useState(0),[error,setError]=useState(''),[busy,setBusy]=useState(false),[form,setForm]=useState(null),[detail,setDetail]=useState(null);
 useEffect(()=>{if(!lockTarget)return;const escape=e=>{if(e.key==='Escape'&&!busy)setLockTarget(null)};document.addEventListener('keydown',escape);return()=>document.removeEventListener('keydown',escape)},[lockTarget,busy]);
 useEffect(()=>{if(form){setFieldErrors({});setError('')}},[Boolean(form),form?.id]);
 useEffect(()=>{detailRequest.current++;setDetail(null);let active=true;setData(null);setError('');api('/admin/accounts?'+new URLSearchParams({search,page,sort,role})).then(v=>{if(active)setData(v)}).catch(e=>{if(active)setError(e.message)});return()=>{active=false}},[search,page,sort,role,refresh]);
 async function save(e){e.preventDefault();setBusy(true);setError('');setFieldErrors({});try{await api('/admin/customers','POST',{fullName:form.fullName,email:form.email,phone:form.phone,address:form.address,password:form.password||null});setForm(null);setRefresh(v=>v+1)}catch(e){setError(e.message);setFieldErrors(e.fields||{})}finally{setBusy(false)}}
 async function disable(c){if(busy)return;setBusy(true);setError('');setNotice('');try{await api('/admin/accounts/'+c.id+(c.deletedAt?'/unlock':''),c.deletedAt?'POST':'DELETE');setLockTarget(null);setNotice(c.deletedAt?'Đã mở khóa tài khoản.':'Đã khóa tài khoản.');setRefresh(v=>v+1)}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function show(c){setBusy(true);try{const version=++detailRequest.current;const value=await api('/admin/accounts/'+c.id);if(version===detailRequest.current)setDetail({...c,...value})}catch(e){setError(e.message)}finally{setBusy(false)}}
 return <div className="accounts-page">
  <div className="toolbar record-heading"><h2>Tài khoản</h2><button className="primary" disabled={busy} onClick={()=>setForm({fullName:'',email:'',phone:'',address:'',password:''})}>+ Thêm khách hàng</button></div>
  <div className="toolbar record-filters"><div className="search"><span>⌕</span><input disabled={busy} aria-label="Tìm tài khoản" placeholder="Tìm theo tên, email hoặc SĐT..." value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}}/></div><select disabled={busy} aria-label="Sắp xếp tài khoản" value={sort} onChange={e=>{setSort(e.target.value);setPage(0)}}><option value="created">Mới nhất</option><option value="name">Tên A–Z</option></select><select disabled={busy} aria-label="Lọc vai trò" value={role} onChange={e=>{setRole(e.target.value);setPage(0)}}><option value="">Tất cả vai trò</option>{Object.entries(roleLabels).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></div>
  {notice&&<p className="alert success" role="status">{notice}</p>}
  {error&&<p className="alert error" role="alert">{error}</p>}
  <MainSubArea>
   <section className="content-panel admin-records accounts-panel">
    {!data&&error?<p className="empty">Không thể tải danh sách tài khoản.</p>:!data?<p role="status">Đang tải…</p>:!data.accounts.length?<p className="empty">Không có tài khoản.</p>:<div className="table-scroll"><table><thead><tr>{['Mã','Họ tên','Email','SĐT','Vai trò','Trạng thái','Ngày tạo','Thao tác'].map(v=><th key={v}>{v}</th>)}</tr></thead><tbody>{data.accounts.map(c=><tr key={c.id} {...rowProps(detail?.id===c.id,()=>show(c))}><td>{c.id.slice(0,8)}</td><td className="record-wrap">{c.fullName}</td><td className="record-wrap" title={c.email}>{c.email}</td><td>{c.phone}</td><td className="record-wrap">{c.roles.map(r=><span className="badge neutral account-role" key={r}>{roleLabels[r]||r}</span>)}</td><td><span className={'badge '+(c.deletedAt?'neutral':'AVAILABLE')}>{c.deletedAt?'Đã khóa':'Hoạt động'}</span></td><td>{new Date(c.createdAt).toLocaleDateString('vi-VN')}</td><td><div className="record-actions"><button className="secondary" disabled={busy} onClick={()=>show(c)}>Chi tiết</button><button className="secondary" disabled={busy||!(c.deletedAt?c.canUnlock:c.canLock)||c.id===currentUserId} onClick={()=>{setError('');setLockTarget(c)}}>{c.deletedAt?'Mở khóa tài khoản':'Khóa tài khoản'}</button></div></td></tr>)}</tbody></table></div>}
    <div className="toolbar"><button className="secondary" disabled={busy||!page} onClick={()=>setPage(v=>v-1)}>Trang trước</button><span>{page+1} / {data?.totalPages||1}</span><button className="secondary" disabled={busy||!data||page+1>=data.totalPages} onClick={()=>setPage(v=>v+1)}>Trang sau</button></div>
   </section>
   {form&&<InlinePanel onClose={()=>setForm(null)} busy={busy} ><form className="dialog customer-dialog" role="region" aria-label="Thông tin tài khoản" onSubmit={save}><div className="dialog-head"><h2>Thêm khách hàng</h2><button type="button" aria-label="Đóng form thêm khách" disabled={busy} onClick={()=>setForm(null)}>✕</button></div><div className="form-grid">{[['fullName','Họ tên','text'],['email','Email','email'],['phone','Số điện thoại','tel'],['address','Địa chỉ','text'],['password','Mật khẩu','password']].map(([k,label,type])=><label className="field" key={k}>{label}<input type={type} required={k!=='address'} minLength={k==='password'?8:undefined} maxLength={k==='password'?72:k==='phone'?20:k==='address'?2000:255} value={form[k]||''} aria-invalid={Boolean(fieldErrors[k])} aria-describedby={fieldErrors[k]?'customer-'+k+'-error':undefined} onChange={e=>setForm({...form,[k]:e.target.value})}/>{fieldErrors[k]&&<small className="invalid" id={'customer-'+k+'-error'}>{fieldErrors[k]}</small>}</label>)}</div>{error&&<p role="alert" className="alert error">{error}</p>}<div className="dialog-foot"><button type="button" className="secondary" disabled={busy} onClick={()=>setForm(null)}>Hủy</button><button className="primary" disabled={busy}>Lưu</button></div></form></InlinePanel>}
   {detail&&<InlinePanel onClose={()=>{detailRequest.current++;setDetail(null)}} busy={busy} valid={!data||data.accounts.some(c=>c.id===detail.id)} panelKey={detail.id}><section className="dialog customer-dialog account-detail" role="region" aria-label="Chi tiết tài khoản">
    <div className="dialog-head"><h2>{detail.fullName}</h2><button aria-label="Đóng chi tiết tài khoản" onClick={()=>{detailRequest.current++;setDetail(null)}}>X</button></div>
    <p className="account-detail-row"><strong>Email:</strong> {detail.email||'—'}</p>
    <p className="account-detail-row"><strong>SĐT:</strong> {detail.phone||'—'}</p>
    <p className="account-detail-row"><strong>Địa điểm:</strong> {detail.address||'—'}</p>
    <div className="account-detail-row account-code-field">
     <strong>Mã tài khoản:</strong>
     <div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${detail.id}`} onClick={()=>navigator.clipboard?.writeText(detail.id)}>#{detail.id.slice(0,8)}</code><span className="copy-hint" title={detail.id} onClick={()=>navigator.clipboard?.writeText(detail.id)}>(Sao chép)</span></div>
    </div>
    {detail.staffId&&<div className="account-detail-row account-code-field">
     <strong>Mã nhân viên:</strong>
     <div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${detail.staffId}`} onClick={()=>navigator.clipboard?.writeText(detail.staffId)}>#{detail.staffId.slice(0,8)}</code><span className="copy-hint" title={detail.staffId} onClick={()=>navigator.clipboard?.writeText(detail.staffId)}>(Sao chép)</span></div>
    </div>}
    {detail.customerId&&<div className="account-detail-row account-code-field">
     <strong>Mã khách hàng:</strong>
     <div className="invoice-code-row"><code className="clickable-code" title={`Sao chép UUID đầy đủ: ${detail.customerId}`} onClick={()=>navigator.clipboard?.writeText(detail.customerId)}>#{detail.customerId.slice(0,8)}</code><span className="copy-hint" title={detail.customerId} onClick={()=>navigator.clipboard?.writeText(detail.customerId)}>(Sao chép)</span></div>
    </div>}
    <p className="account-detail-row"><strong>Vai trò:</strong> {detail.roles.map(r=>roleLabels[r]||r).join(', ')}</p>
    <p className="account-detail-row"><strong>Trạng thái:</strong> {detail.deletedAt?'Đã khóa':'Hoạt động'}</p>
    <p className="account-detail-row"><strong>Ngày tạo:</strong> {new Date(detail.createdAt).toLocaleString('vi-VN')}</p>
    {detail.staffId&&<p className="account-detail-row"><strong>Chức vụ:</strong> {detail.position}</p>}
    {detail.customerId&&<p className="account-detail-row"><strong>Lượt đặt:</strong> {detail.bookingCount}</p>}
    {detail.customerId&&<p className="account-detail-row"><strong>Chi tiêu:</strong> {Number(detail.totalSpent).toLocaleString('vi-VN')} VND</p>}
    {detail.customerId&&onViewInvoices&&<div className="dialog-foot"><button className="secondary" onClick={()=>{setDetail(null);onViewInvoices({id:detail.customerId,name:detail.fullName})}}>Xem hóa đơn đã thanh toán</button></div>}
   </section></InlinePanel>}
  </MainSubArea>
  {lockTarget&&<div className="overlay"><section className="dialog small customer-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-lock-title"><div className="dialog-head"><h2 id="customer-lock-title">{lockTarget.deletedAt?'Mở khóa tài khoản':'Khóa tài khoản'}</h2></div><p>{lockTarget.deletedAt?'Bạn có chắc chắn muốn mở khóa tài khoản này? Người dùng có thể đăng nhập mới. Phiên đăng nhập cũ vẫn bị vô hiệu.':'Bạn có chắc chắn muốn khóa tài khoản này? Người dùng sẽ không thể tiếp tục sử dụng tài khoản.'} Lịch sử đặt sân và hóa đơn vẫn được giữ nguyên.</p>{error&&<p className="alert error" role="alert">{error}</p>}<div className="dialog-foot"><button className="secondary" disabled={busy} onClick={()=>setLockTarget(null)}>Hủy</button><button className="primary" disabled={busy} onClick={()=>disable(lockTarget)}>{lockTarget.deletedAt?'Mở khóa tài khoản':'Khóa tài khoản'}</button></div></section></div>}
 </div>;
}