import React,{useState} from 'react';

export function localDate(date=new Date()){
 return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}

export function inPeriod(value,period,month,today=localDate(),range={}){
 if(!value)return false;
 const date=value.slice(0,10);
 if(range.from&&range.to)return date>=range.from&&date<=range.to;
 if(month)return date.slice(0,7)===month;
 if(period==='DAY')return date===today;
 if(period==='MONTH')return date.slice(0,7)===today.slice(0,7);
 const start=new Date(today+'T00:00:00');
 start.setDate(start.getDate()-(start.getDay()+6)%7);
 const end=new Date(start);end.setDate(end.getDate()+7);
 return date>=localDate(start)&&date<localDate(end);
}

export default function PeriodFilter({period,month,range={},onRangeChange,onChange}){
 const [from,setFrom]=useState(range.from||''),[to,setTo]=useState(range.to||'');
 function choose(p,m){setFrom('');setTo('');onChange(p,m);}
 return <div className="toolbar period-filter" aria-label="Lọc thời gian">
  {[['DAY','Hôm nay'],['WEEK','Tuần này'],['MONTH','Tháng này']].map(([p,label])=><button key={p} aria-pressed={!range.from&&!month&&period===p} className={!range.from&&!month&&period===p?'primary':'secondary'} onClick={()=>choose(p,'')}>{label}</button>)}
  <label className="field">Lịch sử theo tháng<input aria-label="Lịch sử theo tháng" type="month" value={month} onChange={e=>choose('MONTH',e.target.value)}/></label>
  <form className="period-range" onSubmit={e=>{e.preventDefault();if(from&&to&&from<=to)onRangeChange({from,to});}}>
   <label className="field">Từ ngày<input type="date" aria-label="Từ ngày" value={from} max={to||undefined} onChange={e=>setFrom(e.target.value)} required/></label>
   <label className="field">Đến ngày<input type="date" aria-label="Đến ngày" value={to} min={from||undefined} onChange={e=>setTo(e.target.value)} required/></label>
   <button className="secondary" type="submit">Xem lịch sử</button>
  </form>
  {range.from&&<small role="status">Đang xem: {range.from} – {range.to}</small>}
 </div>;
}
