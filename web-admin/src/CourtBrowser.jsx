import {InlinePanel,MainSubArea,rowProps} from './MainSub.jsx';
import React,{useEffect,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const statuses={AVAILABLE:'Hoạt động',MAINTENANCE:'Bảo trì'};
const occupancy={AVAILABLE:'Còn trống',HELD:'Đang giữ chỗ',BOOKED:'Đã đặt',BLOCKED:'Không khả dụng',PAST:'Đã qua giờ'};
const today=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());

export default function CourtBrowser({editingId,onEditingHidden,courts,api,onEdit,onDelete,busy,onRefresh}){
 const [branchPanel,setBranchPanel]=useState(false);
 const [branches,setBranches]=useState([]),[branchId,setBranchId]=useState(''),[courtId,setCourtId]=useState('');
 const [district,setDistrict]=useState('');
 const [query,setQuery]=useState(''),[status,setStatus]=useState(''),[date,setDate]=useState(today),[slotFilter,setSlotFilter]=useState('');
 const [slots,setSlots]=useState([]),[loading,setLoading]=useState(false),[error,setError]=useState(''),[branchError,setBranchError]=useState(''),[refresh,setRefresh]=useState(0);
 useEffect(()=>{
  let active=true;setBranchError('');
  api('/admin/branches').catch(()=>api('/public/branches')).then(x=>{if(active)setBranches(x)}).catch(e=>{if(active)setBranchError(e.message)});
  return()=>{active=false};
 },[courts,refresh]);
 const court=courts.find(c=>c.id===courtId);
 useEffect(()=>{
  let active=true;setSlots([]);setError('');
  if(!courtId||!date||court?.status!=='AVAILABLE'){setLoading(false);return;}
  setLoading(true);
  api('/public/availability?date='+encodeURIComponent(date)+'&courtId='+courtId).then(x=>{if(active)setSlots(x.courts.find(c=>c.courtId===courtId)?.slots||[])}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[courtId,date,courts,refresh]);
 const groups=branches.map(b=>({...b,active:b.active!==false,items:courts.filter(c=>c.branchId===b.id)}));
 for(const c of courts){const id=c.branchId||'unassigned';if(!groups.some(b=>b.id===id))groups.push({id,active:false,name:c.branchId?'Chi nhánh không còn công khai':'Sân chưa phân chi nhánh',district:c.branchId?c.district:null,items:courts.filter(x=>(x.branchId||'unassigned')===id)});}
 const districtName=value=>value?.trim()||'Chưa cập nhật quận / huyện';
 const districts=[...new Set(groups.flatMap(b=>b.district?[districtName(b.district)]:b.items.length?b.items.map(c=>districtName(c.district)):[districtName(null)]))].sort((a,b)=>a.localeCompare(b,'vi',{numeric:true}));
 const inDistrict=(b,c)=>!district||districtName(b.district||c?.district)===district;
 const matches=c=>!status||c.status===status;
 const search=value=>String(value||'').toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi').trim());
 const selected=groups.find(b=>b.id===branchId);
 const visibleGroups=groups.map(b=>({...b,items:b.items.filter(c=>inDistrict(b,c))})).filter(b=>(!district||inDistrict(b)||b.items.length>0)&&(search(b.name)||search(b.district)||b.items.some(c=>search(c.name)))&&(!status||b.items.some(matches)));
 const visibleCourts=(selected?.items||[]).filter(c=>inDistrict(selected,c)&&matches(c)&&(!query||search(c.name)||search(selected?.name))).sort((a,b)=>a.courtNumber-b.courtNumber);
 useEffect(()=>{if(editingId&&!busy&&!(selected?visibleCourts:visibleGroups.flatMap(b=>b.items)).some(c=>c.id===editingId))onEditingHidden?.()},[editingId,busy,query,status,district,branchId,courts]);
 const visibleSlots=slots.filter(s=>!slotFilter||(slotFilter==='AVAILABLE'?s.available:slotFilter==='BOOKED'?['HELD','BOOKED'].includes(s.occupancy):!s.available));

 async function toggleBranchStatus(b){
  if(!b||!b.id||b.id==='unassigned')return;
  const willClose=b.active!==false;
  if(willClose){
   const ok=window.confirm(`Bạn có chắc chắn muốn đóng cửa chi nhánh "${b.name}" không?`);
   if(!ok)return;
  }
  try{
   await api('/admin/branches/'+b.id+'/status','PATCH',{active:!willClose});
   setRefresh(x=>x+1);
   onRefresh();
  }catch(e){
   setBranchError(e.message);
  }
 }

 return <div className="court-browser-page">
  <div className="toolbar"><label className="search"><span>⌕</span><input aria-label="Tìm chi nhánh hoặc sân" placeholder="Tìm chi nhánh, quận hoặc tên sân…" value={query} onChange={e=>setQuery(e.target.value)}/></label><select aria-label="Lọc quận huyện sân" value={district} onChange={e=>{setDistrict(e.target.value);setBranchId('');setCourtId('')}}><option value="">Tất cả quận / huyện</option>{districts.map(d=><option key={d} value={d}>{d}</option>)}</select><select aria-label="Lọc trạng thái sân" value={status} onChange={e=>setStatus(e.target.value)}><option value="">Tất cả trạng thái</option>{Object.entries(statuses).map(([key,text])=><option key={key} value={key}>{text}</option>)}</select><button className="secondary" disabled={busy} onClick={()=>{onRefresh();setRefresh(x=>x+1)}}>↻ Làm mới</button></div>
  {branchError&&<div role="alert" className="alert error">Lỗi chi nhánh: {branchError}</div>}
  {selected&&<div className="toolbar" style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}><div style={{display:'flex',alignItems:'center',gap:'10px'}}><button className="secondary" onClick={()=>{setBranchId('');setCourtId('');setQuery('')}}>← Các chi nhánh</button><h3 style={{margin:0}}>{selected.name}{selected.district?' · '+selected.district:''}</h3><span className={'badge '+(selected.active!==false?'AVAILABLE':'CLOSED')}>{selected.active!==false?'Hoạt động':'Đóng cửa'}</span></div>{selected.id!=='unassigned'&&<button className="secondary" style={selected.active!==false?{color:'#ad5743',borderColor:'#f2b8a2'}:{color:'#174d3a',borderColor:'#93b879'}} onClick={()=>toggleBranchStatus(selected)}>{selected.active!==false?'Đóng cửa chi nhánh':'Mở lại chi nhánh'}</button>}</div>}
  <MainSubArea>
   <section className="content-panel">
    {busy?<p role="status" className="empty">Đang tải dữ liệu…</p>:<div className="table-scroll"><table><thead><tr>{(selected?['Sân','Trạng thái','Loại sân','Giá gốc / khung giờ','Thao tác']:['Chi nhánh','Quận / Huyện','Địa chỉ','Số sân','Trạng thái','Thao tác']).map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>
     {selected?visibleCourts.map(c=><tr key={c.id} {...rowProps(courtId===c.id||editingId===c.id,()=>{setBranchPanel(false);setCourtId(c.id);setSlotFilter('')})}><td><button className="secondary" aria-expanded={courtId===c.id} onClick={()=>{setCourtId(c.id);setSlotFilter('')}}>{c.name}</button></td><td><span className={'badge '+c.status}>{statuses[c.status]||c.status}</span></td><td>{{STANDARD_MAT:'Thảm tiêu chuẩn',WOODEN_FLOOR:'Sàn gỗ'}[c.type]||'—'}</td><td>{money(c.basePrice)}</td><td><div className="actions"><button onClick={()=>onEdit(c)}>Sửa</button><button className="danger" onClick={()=>onDelete(c)}>Xóa</button></div></td></tr>):visibleGroups.map(b=><tr key={b.id} {...rowProps(branchPanel&&branchId===b.id,()=>{setBranchPanel(true);setBranchId(b.id);setCourtId('');setQuery('')})}><td><strong>{b.name}</strong></td><td>{b.district||'—'}</td><td>{b.address||'—'}</td><td>{b.items.length}</td><td><span className={'badge '+(b.active!==false?'AVAILABLE':'CLOSED')}>{b.active!==false?'Hoạt động':'Đóng cửa'}</span></td><td><div className="actions" style={{display:'inline-flex',gap:'8px'}}><button className="secondary" onClick={()=>{setBranchPanel(true);setBranchId(b.id);setCourtId('');setQuery('')}}>Xem sân</button>{b.id!=='unassigned'&&<button className="secondary" style={b.active!==false?{color:'#ad5743',borderColor:'#f2b8a2'}:{color:'#174d3a',borderColor:'#93b879'}} onClick={()=>toggleBranchStatus(b)}>{b.active!==false?'Đóng cửa':'Mở lại'}</button>}</div></td></tr>)}
     {(selected?visibleCourts:visibleGroups).length===0&&<tr><td colSpan={selected?5:6}>Không tìm thấy kết quả.</td></tr>}
    </tbody></table></div>}
   </section>
   {branchPanel&&selected&&!court&&<InlinePanel onClose={()=>setBranchPanel(false)} valid={visibleGroups.some(b=>b.id===branchId)}><section className="dialog" role="region" aria-label="Thông tin chi nhánh"><div className="dialog-head"><h2>{selected.name}</h2><button aria-label="Đóng thông tin chi nhánh" onClick={()=>setBranchPanel(false)}>X</button></div><p>{selected.district}</p><p>{selected.address}</p><p>{selected.items.length} sân</p></section></InlinePanel>}
   {selected&&court&&<InlinePanel onClose={()=>setCourtId('')} valid={visibleCourts.some(c=>c.id===courtId)} panelKey={courtId}><section className="court-schedule" aria-label="Lịch sân"><div className="dialog-head"><h2>Lịch {court.name}</h2><button aria-label="Đóng lịch sân" onClick={()=>setCourtId('')}>X</button></div><div className="toolbar"><label className="field">Ngày xem lịch<input type="date" value={date} onChange={e=>setDate(e.target.value)}/></label><label className="field">Lọc khung giờ<select value={slotFilter} onChange={e=>setSlotFilter(e.target.value)}><option value="">Tất cả khung giờ</option><option value="AVAILABLE">Còn trống</option><option value="BOOKED">Đã đặt / đang giữ chỗ</option><option value="UNAVAILABLE">Không còn trống</option></select></label><button className="secondary" onClick={()=>setRefresh(x=>x+1)}>↻ Cập nhật lịch</button></div><p className="muted">Lịch theo ngày, giờ Việt Nam. Trạng thái có thể thay đổi khi có lượt đặt mới.</p>
    {court.status!=='AVAILABLE'?<p>Sân đang {statuses[court.status]?.toLowerCase()||court.status}, không nhận đặt lịch.</p>:!date?<p>Chọn ngày để xem lịch.</p>:loading?<p role="status">Đang tải lịch…</p>:error?<p role="alert" className="alert error">{error}</p>:<div className="table-scroll"><table><thead><tr><th>Khung giờ</th><th>Giá tiền</th><th>Trạng thái</th></tr></thead><tbody>{visibleSlots.map(s=><tr key={s.timeSlotId}><td>{s.startTime.slice(0,5)} – {s.endTime.slice(0,5)}</td><td>{money(s.price)}</td><td><span className={'badge '+(s.available?'AVAILABLE':'MAINTENANCE')}>{occupancy[s.occupancy]||'Không khả dụng'}</span></td></tr>)}{!visibleSlots.length&&<tr><td colSpan="3">Không có khung giờ phù hợp.</td></tr>}</tbody></table></div>}
   </section></InlinePanel>}
  </MainSubArea>
 </div>;
}