import React,{useEffect,useState} from 'react';

export default function TimeSlotFilters({courts,branches,courtId,onCourtChange}){
 const [district,setDistrict]=useState(''),[branchId,setBranchId]=useState('');
 const activeCourts=courts.filter(c=>c.status==='AVAILABLE');
 const branchFor=c=>branches.find(b=>b.id===c.branchId);
 const districtFor=c=>(branchFor(c)?.district||c.district||'Chưa cập nhật quận / huyện').trim();
 const districts=[...new Set(activeCourts.map(districtFor))].sort((a,b)=>a.localeCompare(b,'vi',{numeric:true}));
 const districtCourts=activeCourts.filter(c=>districtFor(c)===district);
 const branchOptions=[...new Set(districtCourts.map(c=>c.branchId||'unassigned'))].map(id=>({
  id,name:branches.find(b=>b.id===id)?.name||(id==='unassigned'?'Sân chưa phân chi nhánh':'Chi nhánh không còn công khai')
 })).sort((a,b)=>a.name.localeCompare(b.name,'vi'));
 const availableCourts=districtCourts.filter(c=>(c.branchId||'unassigned')===branchId).sort((a,b)=>a.courtNumber-b.courtNumber);

 // Clear stale selections if a refresh removes a court or changes its location/status.
 useEffect(()=>{
  if(district&&!districts.includes(district)){setDistrict('');setBranchId('');onCourtChange('');return;}
  if(branchId&&!branchOptions.some(b=>b.id===branchId)){setBranchId('');onCourtChange('');return;}
  if(courtId&&!availableCourts.some(c=>c.id===courtId))onCourtChange('');
 },[courts,branches,district,branchId,courtId]);

 return <div className="time-slot-filters">
  <label className="field">Quận / Huyện<select aria-label="Chọn quận huyện" value={district} onChange={e=>{setDistrict(e.target.value);setBranchId('');onCourtChange('')}}><option value="">Chọn quận / huyện</option>{districts.map(d=><option key={d} value={d}>{d}</option>)}</select></label>
  <label className="field">Chi nhánh<select aria-label="Chọn chi nhánh xem giá" disabled={!district} value={branchId} onChange={e=>{setBranchId(e.target.value);onCourtChange('')}}><option value="">{district?'Chọn chi nhánh':'Chọn quận / huyện trước'}</option>{branchOptions.map(b=><option key={b.id} value={b.id}>{b.name}</option>)}</select></label>
  <label className="field">Sân<select aria-label="Chọn sân xem giá" disabled={!branchId} value={courtId} onChange={e=>onCourtChange(e.target.value)}><option value="">{branchId?'Chọn sân hoạt động':'Chọn chi nhánh trước'}</option>{availableCourts.map(c=><option key={c.id} value={c.id}>Sân {c.courtNumber}</option>)}</select></label>
 </div>;
}
