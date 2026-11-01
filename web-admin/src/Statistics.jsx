import React,{useEffect,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const periods={MONTH:'Tháng',QUARTER:'Quý',YEAR:'Năm',YEARS:'Các năm'};
const metrics=[['revenue','Tổng thu','totalRevenue'],['expense','Tổng chi','totalExpense'],['profit','Lợi nhuận','totalProfit']];

export default function Statistics({api,initialYear}){
 const currentYear=initialYear||new Date().getFullYear();
 const [period,setPeriod]=useState('MONTH'),[year,setYear]=useState(currentYear),[fromYear,setFromYear]=useState(currentYear-4),[toYear,setToYear]=useState(currentYear);
 const [query,setQuery]=useState('period=MONTH&year='+currentYear),[report,setReport]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [refresh,setRefresh]=useState(0);
 const [metric,setMetric]=useState('ALL');
 const columns=metrics.filter(([key])=>metric==='ALL'||key===metric);
 useEffect(()=>{
  let active=true;setBusy(true);setError('');setReport(null);
  api('/analytics/statistics?'+query).then(v=>{if(active)setReport(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[query,refresh]);
 function submit(e){
  e.preventDefault();
  if(period==='YEARS'&&(Number(fromYear)>Number(toYear)||Number(toYear)-Number(fromYear)>49)){setError('Chọn năm bắt đầu không sau năm kết thúc, tối đa 50 năm.');return;}
  setQuery('period='+period+(period==='YEARS'?'&fromYear='+fromYear+'&toYear='+toYear:'&year='+year));setRefresh(v=>v+1);
 }
 return <div className="analytics-panel">
  <div className="page-heading"><div><span className="eyebrow">THỐNG KÊ</span><h1>Thống kê</h1></div></div>
  <form className="toolbar period-filter statistics-filter" onSubmit={submit}>
   <label className="field">Chỉ tiêu<select value={metric} onChange={e=>setMetric(e.target.value)}><option value="ALL">Tất cả</option>{metrics.map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
   <label className="field">Thống kê theo<select value={period} onChange={e=>setPeriod(e.target.value)}>{Object.entries(periods).map(([v,label])=><option key={v} value={v}>{label}</option>)}</select></label>
   {period==='YEARS'?<>
    <label className="field">Từ năm<input type="number" min="1" max="9998" step="1" required value={fromYear} onChange={e=>setFromYear(e.target.value)}/></label>
    <label className="field">Đến năm<input type="number" min={fromYear||1} max="9998" step="1" required value={toYear} onChange={e=>setToYear(e.target.value)}/></label>
   </>:<label className="field">Năm<input type="number" min="1" max="9998" step="1" required value={year} onChange={e=>setYear(e.target.value)}/></label>}
   <button className="primary" type="submit" disabled={busy}>Xem thống kê</button>
  </form>
  {busy?<p role="status">Đang tải thống kê…</p>:error?<p className="alert error" role="alert">{error}</p>:report&&<>
   <div className="stats three-stats">{columns.map(([key,label,total])=><div key={key}><span>{label}</span><strong>{money(report[total])}</strong></div>)}</div>
   <section className="content-panel"><div className="table-scroll"><table>
    <thead><tr><th scope="col">{periods[report.period]}</th>{report.rows.map(row=><th scope="col" key={row.from}>{row.label}</th>)}<th scope="col">Tổng cộng</th></tr></thead>
    <tbody>{columns.map(([key,label,total])=><tr key={key}><th scope="row">{label}</th>{report.rows.map(row=><td key={row.from}>{money(row[key])}</td>)}<td><strong>{money(report[total])}</strong></td></tr>)}</tbody>
   </table></div></section>
  </>}
 </div>;
}
