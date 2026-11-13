import React,{useEffect,useRef,useState} from 'react';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:2}).format(n||0);
const number=n=>new Intl.NumberFormat('vi-VN',{maximumFractionDigits:2}).format(n||0);
const date=d=>new Date(d+'T00:00:00').toLocaleDateString('vi-VN');
const localDate=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
function range(key){const end=new Date(),start=new Date(end);if(key==='7')start.setDate(start.getDate()-6);if(key==='30')start.setDate(start.getDate()-29);if(key==='month')start.setDate(1);if(key==='year'){start.setMonth(0);start.setDate(1)}return {from:localDate(start),to:localDate(end)}}
const colors=['#174d3a','#47a785','#9bc8ae','#d8a653'];
export default function RevenueBreakdown({api}){
 const [form,setForm]=useState(()=>range('month')),[applied,setApplied]=useState(()=>range('month'));
 const [data,setData]=useState(null),[busy,setBusy]=useState(true),[error,setError]=useState(''),[refresh,setRefresh]=useState(0),[compare,setCompare]=useState(true),[hover,setHover]=useState({});
 const apiRef=useRef(api);apiRef.current=api;
 useEffect(()=>{
  let active=true;setBusy(true);setError('');setData(null);setHover({});
  const params=new URLSearchParams(applied);
  apiRef.current('/analytics/revenue-dashboard?'+params).then(v=>{if(active)setData(v)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setBusy(false)});
  return()=>{active=false};
 },[applied,refresh]);
 function submit(e){e.preventDefault();if(!form.from||!form.to||form.from>form.to){setError('Chọn khoảng ngày hợp lệ, Từ ngày không sau Đến ngày.');return}setApplied({...form});setRefresh(v=>v+1)}
 const trend=data?.trend||[],plot=trend.flatMap(p=>compare?[Number(p.current),Number(p.previous)]:[Number(p.current)]),max=Math.max(1,...plot),x=i=>58+(trend.length===1?330:i*660/(trend.length-1)),y=v=>196-Number(v)/max*156;
 const tip=(key,value)=>setHover(h=>({...h,[key]:value}));
 const positive=(data?.composition||[]).filter(v=>Number(v.amount)>0),growth=data?.growthPercent;
 let offset=0;
 return <section className="revenue-dashboard" aria-label="Dashboard doanh thu">
  <header className="rd-heading"><div><h1>Tổng quan doanh thu</h1><p>Theo dõi doanh thu hóa đơn và hiệu quả kinh doanh.</p></div></header>
  <form className="rd-filters" onSubmit={submit}>
   <label className="field">Từ ngày<input aria-label="Từ ngày" required type="date" value={form.from} onChange={e=>setForm({...form,from:e.target.value})}/></label>
   <label className="field">Đến ngày<input aria-label="Đến ngày" required type="date" value={form.to} onChange={e=>setForm({...form,to:e.target.value})}/></label>
   <div className="rd-quick" role="group" aria-label="Chọn nhanh khoảng ngày">{[['today','Hôm nay'],['7','7 ngày'],['30','30 ngày'],['month','Tháng này'],['year','Năm nay']].map(([key,label])=>{const r=range(key);return <button key={key} type="button" aria-pressed={form.from===r.from&&form.to===r.to} onClick={()=>setForm(r)}>{label}</button>})}</div>
   <button className="primary" disabled={busy}>Xem doanh thu</button>
  </form>
  {error&&<p role="alert" className="alert error">{error}</p>}
  {busy?<p className="rd-state" role="status">Đang tải doanh thu…</p>:data&&<>
   <div className="rd-period">{date(data.from)} – {date(data.to)}<span>So sánh: {date(data.previousFrom)} – {date(data.previousTo)}</span></div>
   <div className="rd-kpis">
    <article><span>Tổng doanh thu</span><strong>{money(data.totalRevenue)}</strong><small>Hóa đơn PAID hợp lệ</small></article>
    <article><span>Tăng trưởng doanh thu</span><strong className={growth===null?'rd-neutral':Number(growth)<0?'rd-down':'rd-up'}>{growth===null?'—':(Number(growth)>0?'↗ ':Number(growth)<0?'↘ ':'→ ')+number(growth)+'%'}</strong><small>{growth===null?'Kỳ trước bằng 0, chưa có cơ sở tính %':'So với kỳ trước cùng số ngày'}</small></article>
    <article><span>Số hóa đơn</span><strong>{number(data.invoiceCount)}</strong><small>Đã thanh toán</small></article>
    <article><span>Giá trị hóa đơn trung bình</span><strong>{money(data.averageInvoice)}</strong><small>Trên mỗi hóa đơn hợp lệ</small></article>
   </div>
   <article className="rd-panel rd-trend"><div className="rd-panel-head"><h2>Xu hướng doanh thu</h2><label className="rd-compare"><input type="checkbox" checked={compare} onChange={e=>{setCompare(e.target.checked);tip('trend',null)}}/>So sánh kỳ trước</label></div>
    {plot.some(v=>v!==0)?<><svg className="rd-line" viewBox="0 0 750 255" role="img" aria-label="Xu hướng doanh thu">
     {[0,.5,1].map(v=><g key={v}><line x1="58" y1={y(v*max)} x2="724" y2={y(v*max)} stroke="#e4ebe5"/><text x="54" y={y(v*max)-5} fontSize="10" textAnchor="start">{number(v*max)} đ</text></g>)}
     {compare&&<polyline fill="none" stroke="#93a798" strokeWidth="2" strokeDasharray="5 4" points={trend.map((p,i)=>`${x(i)},${y(p.previous)}`).join(' ')}/>}
     <polyline fill="none" stroke="#174d3a" strokeWidth="3" points={trend.map((p,i)=>`${x(i)},${y(p.current)}`).join(' ')}/>
     {trend.map((p,i)=><g key={p.from}><circle cx={x(i)} cy={y(p.current)} r={hover.trend===i?5:3} fill="#174d3a"/><rect x={x(i)-Math.min(12,325/Math.max(1,trend.length))} y="22" width={Math.min(24,650/Math.max(1,trend.length))} height="180" fill="transparent" tabIndex="0" role="button" aria-label={date(p.from)+': '+money(p.current)} onMouseEnter={()=>tip('trend',i)} onFocus={()=>tip('trend',i)} onClick={()=>tip('trend',i)} onKeyDown={e=>{if(e.key==='Enter'||e.key===' ')tip('trend',i)}}><title>{date(p.from)} – {date(p.to)}: {money(p.current)}{compare?' · Kỳ trước: '+money(p.previous):''}</title></rect>{(i%Math.max(1,Math.ceil(trend.length/7))===0||i===trend.length-1)&&<text x={x(i)} y="228" textAnchor={i===trend.length-1?'end':i===0?'start':'middle'} fontSize="11">{date(p.from)}</text>}</g>)}
    </svg><div className="rd-legend"><span><i style={{background:colors[0]}}/>Kỳ hiện tại</span>{compare&&<span><i style={{background:'#93a798'}}/>Kỳ trước</span>}</div>{hover.trend!=null&&trend[hover.trend]&&<p className="rd-tooltip" role="status">{date(trend[hover.trend].from)} – {date(trend[hover.trend].to)}: <strong>{money(trend[hover.trend].current)}</strong>{compare&&<span> · {date(trend[hover.trend].previousFrom)} – {date(trend[hover.trend].previousTo)}: {money(trend[hover.trend].previous)}</span>}</p>}</>:<p className="rd-state">Chưa có doanh thu trong kỳ{compare?' và kỳ trước':''}.</p>}
   </article>
   <div className="rd-two">
    <article className="rd-panel rd-composition"><div className="rd-panel-head"><h2>Cơ cấu doanh thu</h2></div>
     {positive.length?<div className="rd-donut-wrap"><svg className="rd-donut" viewBox="0 0 180 180" role="img" aria-label="Cơ cấu doanh thu"><circle cx="90" cy="90" r="62" fill="none" stroke="#edf2ed" strokeWidth="25"/>{positive.map((v,i)=>{const share=Number(v.amount)/Number(data.totalRevenue)*100,start=offset;offset+=share;return <circle key={v.label} cx="90" cy="90" r="62" fill="none" stroke={colors[i%colors.length]} strokeWidth={hover.composition===i?30:25} pathLength="100" strokeDasharray={`${share} ${100-share}`} strokeDashoffset={-start} transform="rotate(-90 90 90)" tabIndex="0" role="button" aria-label={v.label+': '+money(v.amount)+' · '+number(v.percent)+'%'} onMouseEnter={()=>tip('composition',i)} onFocus={()=>tip('composition',i)} onClick={()=>tip('composition',i)} onKeyDown={e=>{if(e.key==='Enter'||e.key===' ')tip('composition',i)}}><title>{v.label}: {money(v.amount)} ({number(v.percent)}%)</title></circle>})}<text x="90" y="87" textAnchor="middle" fontSize="12">Cơ cấu</text><text x="90" y="107" textAnchor="middle" fontSize="18" fontWeight="700">100%</text></svg><div className="rd-slices">{positive.map((v,i)=><button key={v.label} aria-pressed={hover.composition===i} onMouseEnter={()=>tip('composition',i)} onFocus={()=>tip('composition',i)} onClick={()=>tip('composition',i)}><i style={{background:colors[i%colors.length]}}/><span>{v.label}<strong>{money(v.amount)}</strong></span><b>{number(v.percent)}%</b></button>)}</div></div>:<p className="rd-state">Chưa có dữ liệu phù hợp để phân bổ cơ cấu doanh thu.</p>}
     {hover.composition!=null&&positive[hover.composition]&&<p className="rd-tooltip" role="status">{positive[hover.composition].label}: {money(positive[hover.composition].amount)} · {number(positive[hover.composition].percent)}%</p>}
    </article>
    <article className="rd-panel rd-payments"><div className="rd-panel-head"><h2>Phương thức thanh toán</h2></div>
     {data.payments.some(v=>Number(v.amount)>0)?<><svg viewBox="0 0 400 225" role="img" aria-label="Doanh thu CASH và VNPAY">{data.payments.map((v,i)=>{const h=Number(v.amount)/Math.max(1,...data.payments.map(p=>Number(p.amount)))*128;return <g key={v.label}><text x={115+i*170} y={178-h-9} textAnchor="middle" fontSize="11">{money(v.amount)}</text><rect x={73+i*170} y={178-h} width="84" height={Math.max(2,h)} rx="5" fill={hover.payment===i?'#0e3527':colors[i]} tabIndex="0" role="button" aria-label={v.label+': '+money(v.amount)+' · '+number(v.percent)+'%'} onMouseEnter={()=>tip('payment',i)} onFocus={()=>tip('payment',i)} onClick={()=>tip('payment',i)} onKeyDown={e=>{if(e.key==='Enter'||e.key===' ')tip('payment',i)}}><title>{v.label}: {money(v.amount)} ({number(v.percent)}%)</title></rect><text x={115+i*170} y="200" textAnchor="middle" fontSize="12">{v.label} · {number(v.percent)}%</text></g>})}</svg><div className="rd-legend">{data.payments.map((v,i)=><span key={v.label}><i style={{background:colors[i]}}/>{v.label}</span>)}</div></>:<p className="rd-state">Chưa có khoản thu CASH/VNPAY khớp hóa đơn trong kỳ.</p>}
     {hover.payment!=null&&<p className="rd-tooltip" role="status">{data.payments[hover.payment].label}: {money(data.payments[hover.payment].amount)} · {number(data.payments[hover.payment].percent)}%</p>}
     {Number(data.paymentUnallocated)>0&&<p className="rd-note">Chưa gán phương thức: {money(data.paymentUnallocated)}. Tỷ trọng tính trên tổng doanh thu hóa đơn.</p>}
    </article>
   </div>
   <article className="rd-panel rd-top"><div className="rd-panel-head"><h2>Top 5 sân có doanh thu cao nhất</h2><span>Tiền sân sau giảm giá</span></div>
    {data.topCourts.length?<div className="rd-bars">{data.topCourts.map((v,i)=><button key={v.label+'-'+i} className={hover.court===i?'is-selected':''} aria-label={v.label+': '+money(v.amount)} onMouseEnter={()=>tip('court',i)} onFocus={()=>tip('court',i)} onClick={()=>tip('court',i)}><span className="rd-court-name" title={v.label}>{i+1}. {v.label}</span><span className="rd-track"><i style={{width:Math.max(1,Number(v.amount)/Number(data.topCourts[0].amount)*100)+'%'}}/></span><strong>{money(v.amount)}</strong></button>)}</div>:<p className="rd-state">Chưa có dữ liệu tiền sân đủ chính xác để xếp hạng.</p>}
    {hover.court!=null&&data.topCourts[hover.court]&&<p className="rd-tooltip" role="status">{data.topCourts[hover.court].label}: {money(data.topCourts[hover.court].amount)} · {number(data.topCourts[hover.court].percent)}% tiền sân</p>}
   </article>
   {data.warnings.length>0&&<div className="rd-notes" aria-label="Lưu ý dữ liệu">{data.warnings.map(w=><p key={w}>{w}</p>)}</div>}
  </>}
 </section>
}
