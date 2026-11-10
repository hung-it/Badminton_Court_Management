import React,{useEffect,useMemo,useRef,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const number=n=>new Intl.NumberFormat('vi-VN').format(n||0);
const tabs=[['staff','Nhân viên','staffName','staffId','staff'],['customer','Khách hàng','customerName','customerId','customers'],['product','Sản phẩm','productName','productId','products'],['court','Tiền sân','courtName','courtId','courts']];

export default function RevenueBreakdown({api,initialDimension}){
 const [dimension,setDimension]=useState(initialDimension||'staff');
 const [period,setPeriod]=useState('QUARTER');
 const [year,setYear]=useState(String(new Date().getFullYear()));
 const [quarter,setQuarter]=useState('');
 const [query,setQuery]=useState('period=QUARTER&year='+new Date().getFullYear()+'&detail=true');
 const [report,setReport]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[search,setSearch]=useState(''),[refresh,setRefresh]=useState(0);
 const apiRef=useRef(api);apiRef.current=api;
 useEffect(()=>{
  let active=true;setBusy(true);setError('');setReport(null);
  apiRef.current('/analytics/revenue-breakdown?'+query).then(v=>{if(active)setReport(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[query,refresh]);
 const [key,label,nameKey,idKey,responseKey]=tabs.find(t=>t[0]===dimension);
 const rows=report?.[responseKey]||[];
 const visibleRows=useMemo(()=>rows.filter(row=>(row[nameKey]||'').toLocaleLowerCase('vi').includes(search.trim().toLocaleLowerCase('vi'))),[rows,nameKey,search]);
 const maxRevenue=Math.max(0,...rows.map(row=>Number(row.total||0)));
 function submit(e){e.preventDefault();const value=Number(year);if(!Number.isInteger(value)||value<1||value>9998){setError('Nhập năm hợp lệ từ 1 đến 9998.');return;}setQuarter('');setQuery('period='+period+'&year='+value+'&detail=true');setRefresh(v=>v+1);}
 function pickQuarter(value){const quarterNumber=value?value.match(/^Q([1-4])\//)?.[1]:'';setQuarter(value);setQuery('period='+period+'&year='+year+'&detail=true'+(quarterNumber?'&quarter='+quarterNumber:''));setRefresh(v=>v+1);}
 function pickPeriod(value){setPeriod(value);setQuarter('');}
 const detail=report?.details?.find(d=>d.periodKey===(quarter||report?.periods?.[0]?.key));
 return <div className="analytics-panel revenue-breakdown">
  <div className="page-heading"><div><span className="eyebrow">PHÂN TÍCH DOANH THU</span><h1>Doanh thu theo đối tượng</h1></div></div>
  <div className="breakdown-tabs" role="tablist" aria-label="Nhóm báo cáo">
   {tabs.map(([value,text])=><button key={value} role="tab" aria-selected={dimension===value} className={dimension===value?'primary':'secondary'} onClick={()=>{setDimension(value);setSearch('');}}>{text}</button>)}
  </div>
  <form className="toolbar period-filter breakdown-filter" onSubmit={submit}>
   <label className="field">Thống kê theo<select aria-label="Kỳ báo cáo" value={period} onChange={e=>pickPeriod(e.target.value)}><option value="QUARTER">Quý</option><option value="YEAR">Năm</option></select></label>
   <label className="field year-field">Năm<input aria-label="Năm báo cáo" type="number" min="1" max="9998" value={year} onChange={e=>setYear(e.target.value)}/></label>
   <button className="primary" type="submit" disabled={busy}>Xem báo cáo</button>
  </form>
  {busy?<p role="status" className="empty">Đang tải báo cáo…</p>:error?<p className="alert error" role="alert">{error}</p>:report&&<>
   <div className="stats three-stats">
    <div><span>{dimension==='court'?'Tổng tiền sân':dimension==='product'?'Tổng doanh thu sản phẩm':'Tổng doanh thu hóa đơn'}</span><strong>{money(rows.reduce((sum,row)=>sum+Number(row.total||0),0))}</strong></div>
    <div><span>Số {label.toLocaleLowerCase('vi')}</span><strong>{rows.length}</strong></div>
    <div><span>Kỳ báo cáo</span><strong>{report.period==='YEAR'?'Năm '+report.year:'4 quý năm '+report.year}</strong></div>
   </div>
   {dimension==='product'&&<p className="muted breakdown-note">Doanh thu sản phẩm đã phân bổ giảm giá cấp hóa đơn và tiền sân, nên cộng với tổng tiền sân sẽ bằng tổng doanh thu hóa đơn.</p>}
   {dimension==='court'&&<p className="muted breakdown-note">Chỉ tính tiền sân của hóa đơn đã thanh toán, không tính tiền hàng hóa và dịch vụ.</p>}
   <section className="content-panel">
    <div className="toolbar breakdown-toolbar"><h3>{label} · {report.period==='YEAR'?'Năm '+report.year:'Quý năm '+report.year}</h3><label className="field">Tìm {label.toLocaleLowerCase('vi')}<input type="search" value={search} onChange={e=>setSearch(e.target.value)} placeholder={'Tìm theo tên '+label.toLocaleLowerCase('vi')}/></label><span className="count">{visibleRows.length} / {rows.length}</span></div>
    {!visibleRows.length?<p className="empty">{rows.length?'Không tìm thấy kết quả phù hợp.':'Chưa có dữ liệu trong kỳ này.'}</p>:<div className="table-scroll"><table className="breakdown-table"><thead><tr><th scope="col">{label}</th>{report.periods.map(p=><th scope="col" key={p.key}>{p.label}</th>)}{dimension==='product'&&<th scope="col">Tổng số lượng</th>}<th scope="col">Tổng cộng</th><th scope="col">So với cao nhất</th></tr></thead><tbody>
     {visibleRows.map(row=>{const values=report.periods.map(p=>row.values?.[p.key]||0);const total=Number(row.total||0);const share=maxRevenue?Math.max(0,Math.min(100,total/maxRevenue*100)):0;return <tr key={row[idKey]||'unattributed'}><th scope="row">{row[nameKey]||'—'}</th>{report.periods.map((p,i)=><td key={p.key}>{dimension==='product'?<><span>{number(row.quantities?.[p.key])}</span><small className="breakdown-money">{money(values[i])}</small></>:money(values[i])}</td>)}{dimension==='product'&&<td>{number(row.totalQuantity)}</td>}<td><strong>{money(total)}</strong></td><td><div className="breakdown-bar" role="img" aria-label={Math.round(share)+'% so với dòng cao nhất'}><span style={{width:share+'%'}}/></div></td></tr>})}
    </tbody></table></div>}
   </section>
   <section className="content-panel breakdown-detail-panel">
    <div className="toolbar breakdown-toolbar"><h3>Chi tiết nội dung{report.period==='YEAR'?'':' theo quý'}</h3><div className="breakdown-quarters" role="group" aria-label="Chọn kỳ chi tiết">{report.period==='YEAR'?null:report.periods.map(p=><button key={p.key} type="button" aria-pressed={(quarter||report.periods[0]?.key)===p.key} className={(quarter||report.periods[0]?.key)===p.key?'primary':'secondary'} onClick={()=>pickQuarter(p.key)}>{p.label}</button>)}</div></div>
    {!detail?<p className="empty">Chưa có dữ liệu chi tiết cho kỳ này.</p>:<>
     <div className="table-scroll"><table className="breakdown-detail-table"><thead><tr><th scope="col">Sản phẩm</th><th scope="col">Nhân viên thêm hàng</th><th scope="col">Khách hàng</th><th scope="col">Số lượng</th><th scope="col">Tiền dòng hàng</th></tr></thead><tbody>
      {!detail.products.length?<tr><td colSpan="5" className="empty-cell">Không có dòng hàng trong kỳ này.</td></tr>:detail.products.map(row=><tr key={(row.productId||'p')+'-'+(row.staffId||'s')+'-'+(row.customerId||'c')}><th scope="row">{row.productName}</th><td>{row.staffName}</td><td>{row.customerName}</td><td>{number(row.totalQuantity)}</td><td><strong>{money(row.total)}</strong></td></tr>)}
     </tbody></table></div>
     <div className="breakdown-totals">
      <div className="breakdown-total-line"><span>Tổng tiền sản phẩm</span><strong>{money(detail.productTotal)}</strong></div>
      <div className="breakdown-total-line"><span>+ Tiền sân</span><strong>{money(detail.courtTotal)}</strong></div>
      <div className="breakdown-total-line grand"><span>= Tổng doanh thu hóa đơn</span><strong>{money(detail.invoiceTotal)}</strong></div>
     </div>
    </>}
   </section>
  </>}
 </div>;
}
