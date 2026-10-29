import React,{useState,useEffect} from 'react';
import ProductPromotions from './Promotions.jsx';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const labels={STANDARD_MAT:'Thảm tiêu chuẩn',WOODEN_FLOOR:'Sàn gỗ',AVAILABLE:'Hoạt động',MAINTENANCE:'Bảo trì',CLOSED:'Đóng cửa',GOODS:'Hàng hóa',SERVICE:'Dịch vụ'};
const masterTabs=[['courts','Sân cầu'],['time-slots','Khung giờ & giá'],['categories','Danh mục'],['products','Hàng hóa & dịch vụ'],['product-promotions','Khuyến mãi']];
const opTabs=[['pos','Bán tại quầy'],['checkout','Trả sân'],['active-bookings','Sân đã đặt'],['inventory','Nhập kho'],['analytics','Doanh thu & Báo cáo']];
const allTabs=[...masterTabs,...opTabs];
const initial={courts:{courtNumber:1,name:'',type:'STANDARD_MAT',status:'AVAILABLE',basePrice:100000,district:'',address:''},'time-slots':{startTime:'06:00',endTime:'07:00',priceMultiplier:1},categories:{categoryName:''},products:{categoryId:'',name:'',type:'GOODS',unit:'cái',price:0,stockQuantity:0}};
function defaultMultiplier(start,end){return start>='17:00'&&end<='21:00'?1.5:1;}
function validateCourtLocation(form){
 if(!form.address?.trim())return;
 if(!form.district?.trim())throw new Error('Vui lòng nhập quận / huyện cho địa chỉ sân');
 const normalize=value=>value.trim().toLocaleLowerCase('vi').replace(/^quận\s+/,'');
 const known=['bình thạnh','tân bình','gò vấp'];
 for(const part of form.address.split(/[,;]/))if((/^quận\s+\d+$/i.test(part.trim())||known.includes(normalize(part)))&&normalize(part)!==normalize(form.district))throw new Error('Địa chỉ sân không khớp quận / huyện');
}

export default function App(){
 const [session,setSession]=useState(()=>{try{return JSON.parse(sessionStorage.getItem('bcm-session'))}catch{return null}});
 const [tab,setTab]=useState(()=>session?.user?.roles?.includes('ADMIN')?'courts':'pos'),[data,setData]=useState({courts:[],'time-slots':[],categories:[],products:[],'product-promotions':[]});
 const [busy,setBusy]=useState(false),[saving,setSaving]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[query,setQuery]=useState(''),[filter,setFilter]=useState('');
 const [modal,setModal]=useState(null),[form,setForm]=useState({}),[fieldErrors,setFieldErrors]=useState({}),[deleteTarget,setDeleteTarget]=useState(null);
 const [credentials,setCredentials]=useState({email:'',password:''}),[quoteCourt,setQuoteCourt]=useState(''),[quote,setQuote]=useState(null);

 // POS state
 const [posCart,setPosCart]=useState([]);
 const [posQuote,setPosQuote]=useState(null),[posQuoteBusy,setPosQuoteBusy]=useState(false),[posQuoteError,setPosQuoteError]=useState('');
 const [posSearch,setPosSearch]=useState('');
 const [posHistory,setPosHistory]=useState([]);
 const [posCustomerId,setPosCustomerId]=useState('');
 const [customers,setCustomers]=useState([]);

 // Checkout state
 const [checkoutBookingId,setCheckoutBookingId]=useState('');
 const [posBookingId,setPosBookingId]=useState(''),[courtVoucher,setCourtVoucher]=useState(''),[counterReceipt,setCounterReceipt]=useState(false);
 const [checkoutPaymentMethod,setCheckoutPaymentMethod]=useState('CASH');
 const [checkoutSlip,setCheckoutSlip]=useState(null);
 const [checkoutHistory,setCheckoutHistory]=useState([]);
  const [activeBookings,setActiveBookings]=useState([]);
  const [activeBusy,setActiveBusy]=useState(false);
  const [activeError,setActiveError]=useState('');

 // Inventory state
 const [importOrders,setImportOrders]=useState([]);
 const [suppliers,setSuppliers]=useState([]);
 const [importModalOpen,setImportModalOpen]=useState(false);
 const [importForm,setImportForm]=useState({supplierId:'',productId:'',quantity:10,importPrice:50000});

 // Analytics state
 const [analyticsPeriod,setAnalyticsPeriod]=useState('DAY');
 const [revenueData,setRevenueData]=useState(null);

 function logout(){sessionStorage.removeItem('bcm-session');setSession(null);setData({courts:[],'time-slots':[],categories:[],products:[],'product-promotions':[]});setModal(null);setDeleteTarget(null);}
 async function api(path,method='GET',body,token=session?.accessToken){
  const res=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  let json;try{json=await res.json()}catch{throw new Error('Không thể đọc phản hồi máy chủ. Kiểm tra backend.');}
  if(!res.ok||!json.success){if(res.status===401){logout();}const e=new Error(json.message||'Không thực hiện được thao tác');e.fields=json.data;throw e;}return json.data;
 }

 async function load(){
  setBusy(true);setError('');
  try{
   if(!session?.user?.roles?.includes('ADMIN')){const [products,promotions]=await Promise.all([api('/pos/products'),api('/pos/product-promotions')]);setData({courts:[],'time-slots':[],categories:[],products,'product-promotions':promotions});return;}
   const values=await Promise.all(masterTabs.map(([key])=>api('/admin/'+key)));
   setData(Object.fromEntries(masterTabs.map(([key],i)=>[key,values[i]])));
  }catch(e){setError(e.message)}finally{setBusy(false)}
 }

 async function loadOperationalData(){
  try{
   if(tab==='pos'){
    const custs=await api('/pos/customers').catch(()=>[]);
    setCustomers(custs||[]);
   }else if(tab==='inventory'){
    const [orders,supps]=await Promise.all([api('/import-orders').catch(()=>[]),api('/suppliers').catch(()=>[])]);
    setImportOrders(orders||[]);setSuppliers(supps||[]);
    if(supps&&supps.length>0&&!importForm.supplierId){
     setImportForm(f=>({...f,supplierId:supps[0].id}));
    }
   }else if(tab==='analytics'){
    const rev=await api('/analytics/revenue?period='+analyticsPeriod).catch(()=>null);
    setRevenueData(rev);
      }else if(tab==='active-bookings'){
        setActiveBusy(true);
        setActiveError('');
        try{
          const data=await api('/checkout/active-bookings');
          setActiveBookings(data||[]);
        }catch(e){setActiveError(e.message);}
        finally{setActiveBusy(false);}
   }
  }catch(err){console.error(err);}
 }

 useEffect(()=>{if(session)load()},[session]);
 useEffect(()=>{
  setQuery('');setFilter('');setNotice('');setQuote(null);
  if(session)loadOperationalData();
 },[tab,analyticsPeriod]);

 async function login(e){e.preventDefault();setSaving(true);setError('');try{const s=await api('/auth/login','POST',credentials,null);if(!s.user?.roles?.some(r=>['ADMIN','STAFF'].includes(r)))throw new Error('Tài khoản cần quyền ADMIN hoặc STAFF.');setTab(s.user.roles.includes('ADMIN')?'courts':'pos');sessionStorage.setItem('bcm-session',JSON.stringify(s));setSession(s);setCredentials({email:'',password:''})}catch(e){setError(e.message)}finally{setSaving(false)}}
 function open(item=null){setModal({id:item?.id||null});setForm(item?{...item,...(tab==='courts'?{courtNumber:item.courtCode??item.courtNumber,type:'STANDARD_MAT'}:{})}:{...initial[tab],...(tab==='courts'?{courtNumber:Math.max(0,...data.courts.map(c=>c.courtCode??c.courtNumber))+1}:{}),...(tab==='products'?{categoryId:data.categories[0]?.id||''}:{})});setFieldErrors({});setError('');}
 function change(key,value){setForm(old=>{const next={...old,[key]:value};if(tab==='products'&&key==='type'&&value==='SERVICE')next.stockQuantity=0;if(tab==='time-slots'&&(key==='startTime'||key==='endTime'))next.priceMultiplier=defaultMultiplier(next.startTime.slice(0,5),next.endTime.slice(0,5));return next});}
 async function save(e){e.preventDefault();setSaving(true);setFieldErrors({});setError('');try{let payload={...form};if(tab==='courts')validateCourtLocation(payload);for(const key of ['courtNumber','basePrice','priceMultiplier','price','stockQuantity'])if(key in payload)payload[key]=Number(payload[key]);await api('/admin/'+tab+(modal.id?'/'+modal.id:''),modal.id?'PUT':'POST',payload);setModal(null);setNotice('Đã lưu dữ liệu thành công.');await load()}catch(e){setError(e.message);if(e.fields&&typeof e.fields==='object')setFieldErrors(e.fields)}finally{setSaving(false)}}
 async function remove(){setSaving(true);setError('');try{await api('/admin/'+tab+'/'+deleteTarget.id,'DELETE');setDeleteTarget(null);setNotice('Đã xóa dữ liệu thành công.');await load()}catch(e){setError(e.message)}finally{setSaving(false)}}

useEffect(()=>{
 let active=true;
 if(!session||tab!=='pos'||!posCart.length){setPosQuote(null);setPosQuoteBusy(false);setPosQuoteError('');return;}
 setPosQuote(null);setPosQuoteBusy(true);setPosQuoteError('');
 const timer=setTimeout(async()=>{try{
  const quote=await api('/pos/quote','POST',{paymentMethod:'CASH',items:posCart.map(i=>({productId:i.id,quantity:i.quantity}))});
  if(active)setPosQuote(quote);
 }catch(e){if(active)setPosQuoteError(e.message)}finally{if(active)setPosQuoteBusy(false)}},150);
 return ()=>{active=false;clearTimeout(timer)};
},[session,tab,posCart]);
 // POS handlers
 function addToCart(p){
  setPosCart(old=>{
   const ex=old.find(x=>x.id===p.id);
   if(ex)return old.map(x=>x.id===p.id?{...x,quantity:x.quantity+1}:x);
   return [...old,{id:p.id,name:p.name,unit:p.unit,price:p.price,quantity:1,stockQuantity:p.stockQuantity}];
  });
 }
 function updateCartQuantity(id,delta){
  setPosCart(old=>old.map(x=>x.id===id?{...x,quantity:Math.max(1,x.quantity+delta)}:x));
 }
 function removeFromCart(id){
  setPosCart(old=>old.filter(x=>x.id!==id));
 }
 async function handlePosSale(){
  if(!posCart.length)return;
  setSaving(true);setError('');
  try{
   // Never pass session.user.id because that is in users table, not customers table
   const custId=posCustomerId||null;
   const payload={
    customerId:custId,
    bookingId:posBookingId||null,
    paymentMethod:'CASH',
    courtFee:0,
    items:posCart.map(i=>({productId:i.id,quantity:i.quantity}))
   };
   const inv=await api('/pos/sales','POST',payload);
   const totalAmt=inv.total_amount??inv.totalAmount;
   const discountAmt=inv.discountAmount??inv.discount_amount??0;
   setPosHistory(h=>[{id:inv.id||`#HD-${Date.now().toString().slice(-4)}`,time:new Date().toLocaleTimeString('vi-VN'),total:totalAmt,discount:discountAmt,status:inv.status,items:[...posCart]},...h]);
   setPosCart([]);
   setNotice(posBookingId?'Đã thêm hàng vào booking. Thu phần hàng còn lại khi kết toán trả sân.':'Bán hàng thành công! Hóa đơn đã được lập.');
   await load();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 // Checkout handlers
 async function createCheckoutSlip(){
  setError('');setSaving(true);
  try{const v=await api('/checkout/'+checkoutBookingId);setCheckoutSlip({...v,id:'Xem trước',bookingId:checkoutBookingId,courtFee:v.court_fee,productFee:v.product_fee,totalAmount:v.total_amount,status:'DRAFT'});}
  catch(e){setError(e.message)}finally{setSaving(false)}
 }
 async function confirmCheckout(){
  if(!checkoutSlip||checkoutSlip.status==='PAID')return;
  setSaving(true);setError('');
  try{
   const res=await api('/checkout/'+checkoutSlip.bookingId,'POST',{paymentMethod:checkoutPaymentMethod});
   const paidSlip={...checkoutSlip,...res,status:'PAID',paymentMethod:checkoutPaymentMethod,invoiceId:res.invoiceId,totalAmount:res.total_amount??res.totalAmount??checkoutSlip.totalAmount};
   setCheckoutSlip(paidSlip);
   setCheckoutHistory(h=>[paidSlip,...h]);
   setNotice('Kết toán trả sân thành công! Hóa đơn '+res.invoiceId+' đã lưu.');
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 // Inventory handlers
 async function handleCreateImportOrder(e){
  e.preventDefault();
  setSaving(true);setError('');
  try{
   const payload={
    supplierId:importForm.supplierId,
    importDate:new Date().toISOString().slice(0,10),
    details:[{productId:importForm.productId||data.products.find(p=>p.type==='GOODS')?.id||data.products[0]?.id,quantity:Number(importForm.quantity),importPrice:Number(importForm.importPrice)}]
   };
   await api('/import-orders','POST',payload);
   setImportModalOpen(false);
   setNotice('Lập phiếu nhập thành công.');
   await loadOperationalData();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }
 async function handleApproveImportOrder(orderId){
  setSaving(true);setError('');
  try{
   await api('/import-orders/'+orderId+'/approve','POST');
   setNotice('Duyệt phiếu nhập thành công! Tồn kho đã được cập nhật.');
   await loadOperationalData();
   await load();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 const rows=data[tab]?data[tab].filter(x=>{const text=[x.name,x.categoryName,x.courtNumber,x.startTime,x.endTime,labels[x.type],labels[x.status]].join(' ').toLocaleLowerCase('vi');return text.includes(query.toLocaleLowerCase('vi'))&&(!filter||(tab==='products'?x.categoryId===filter:x.status===filter))}):[];
 const title=allTabs.find(([k])=>k===tab)?.[1]||'';
 function field(key,label,type='text',extra={}){return <label className="field" key={key}>{label}<input aria-label={label} name={key} type={type} value={form[key]??''} onChange={e=>change(key,e.target.value)} required {...extra}/>{fieldErrors[key]&&<small className="invalid">{fieldErrors[key]}</small>}</label>}
 function select(key,label,options){return <label className="field" key={key}>{label}<select aria-label={label} name={key} value={form[key]??''} onChange={e=>change(key,e.target.value)} required>{options.map(([v,l])=><option key={v} value={v}>{l}</option>)}</select>{fieldErrors[key]&&<small className="invalid">{fieldErrors[key]}</small>}</label>}

 if(!session)return <div className="login-layout"><section className="login-story"><div className="brand">BCM<span>COURT MANAGEMENT</span></div><div><span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span><h1>Mọi sân chơi.<br/>Một nơi quản lý.</h1><p>Quản lý sân, khung giờ và danh mục hàng hóa<br/>để vận hành câu lạc bộ mỗi ngày.</p><div className="court-illustration"><i/><b/></div></div><small>BADMINTON COURT MANAGEMENT</small></section><section className="login-panel"><form onSubmit={login}><span className="eyebrow">CHÀO MỪNG TRỞ LẠI</span><h2>Đăng nhập quản trị</h2><p className="muted">Sử dụng tài khoản Admin hoặc Nhân viên của bạn.</p><label className="field">Email<input type="email" required autoComplete="username" value={credentials.email} onChange={e=>setCredentials({...credentials,email:e.target.value})}/></label><label className="field">Mật khẩu<input type="password" required autoComplete="current-password" value={credentials.password} onChange={e=>setCredentials({...credentials,password:e.target.value})}/></label>{error&&<div role="alert" className="alert error">{error}</div>}

<button className="primary wide" disabled={saving}>{saving?'Đang đăng nhập…':'Đăng nhập →'}</button></form></section></div>;

 return <div className="app-shell"><aside className="sidebar"><div className="brand">BCM<span>COURT MANAGEMENT</span></div><span className="nav-caption">QUẢN LÝ DỮ LIỆU</span><nav>{masterTabs.filter(([key])=>session.user?.roles?.includes('ADMIN')||key==='product-promotions').map(([key,label])=><button key={key} className={tab===key?'nav active':'nav'} onClick={()=>setTab(key)}>{label}</button>)}</nav><span className="tab-section-label">VẬN HÀNH & POS</span><nav>{opTabs.filter(([key])=>session.user?.roles?.includes('ADMIN')||['pos','checkout','active-bookings'].includes(key)).map(([key,label])=><button key={key} className={tab===key?'nav active':'nav'} onClick={()=>setTab(key)}>{label}</button>)}</nav><div className="sidebar-bottom"><div className="avatar">A</div><div><strong>{session.user?.fullName||'Quản trị viên'}</strong><small>{session.user?.roles?.includes('ADMIN')?'Administrator':'Nhân viên'}</small></div><button aria-label="Đăng xuất" title="Đăng xuất" onClick={logout}>↗</button></div></aside><main><header className="topbar"><span>Vận hành / <strong>{title}</strong></span><span className="live"><i/> Web Admin</span><button className="mobile-logout secondary" onClick={logout}>Đăng xuất</button></header><div className="workspace">

 {error&&!modal&&!deleteTarget&&<div role="alert" className="alert error">{error} <button onClick={load}>Thử lại</button></div>}

{notice&&<div role="status" className="alert success">{notice}</div>}



 {tab==='product-promotions'&&<ProductPromotions promotions={data['product-promotions']||[]} products={data.products} api={api} onChanged={load} canManage={session.user?.roles?.includes('ADMIN')}/>}
 {/* MASTER DATA TABS */}
 {['courts','time-slots','categories','products'].includes(tab)&&<>
 <div className="page-heading"><div><span className="eyebrow">MASTER DATA</span><h1>{title}</h1><p className="muted">{tab==='courts'?'Theo dõi và cấu hình hệ thống sân của câu lạc bộ.':tab==='time-slots'?'Thiết lập giờ chơi và hệ số giá theo từng khung giờ.':tab==='categories'?'Tổ chức hàng hóa và dịch vụ theo danh mục.':'Quản lý thông tin bán hàng và tồn kho ban đầu.'}</p></div><button className="primary" onClick={()=>open()} disabled={busy||(tab==='products'&&!data.categories.length)}>＋ {tab==='courts'?'Thêm sân':tab==='time-slots'?'Thêm khung giờ':tab==='categories'?'Thêm danh mục':'Thêm sản phẩm'}</button></div>
 <div className="stats"><div><span>Tổng số sân</span><strong>{data.courts.length.toString().padStart(2,'0')}</strong><small>Trong hệ thống</small></div><div><span>Sân hoạt động</span><strong>{data.courts.filter(c=>c.status==='AVAILABLE').length.toString().padStart(2,'0')}</strong><small className="green">Sẵn sàng phục vụ</small></div><div><span>Khung giờ</span><strong>{data['time-slots'].length.toString().padStart(2,'0')}</strong><small>Cấu hình trong ngày</small></div><div><span>Hàng hóa & dịch vụ</span><strong>{data.products.length.toString().padStart(2,'0')}</strong><small>{data.categories.length} danh mục</small></div></div>
 <section className="content-panel"><div className="toolbar"><label className="search"><span>⌕</span><input aria-label="Tìm kiếm" placeholder="Tìm kiếm…" value={query} onChange={e=>setQuery(e.target.value)}/></label>{tab==='courts'&&<select aria-label="Lọc trạng thái" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">Tất cả trạng thái</option>{['AVAILABLE','MAINTENANCE','CLOSED'].map(v=><option key={v} value={v}>{labels[v]}</option>)}</select>}{tab==='products'&&<select aria-label="Lọc danh mục" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">Tất cả danh mục</option>{data.categories.map(c=><option key={c.id} value={c.id}>{c.categoryName}</option>)}</select>}<span className="count">{rows.length} kết quả</span><button className="secondary" onClick={load} disabled={busy}>↻ Làm mới</button></div>
 {busy?<div className="empty" role="status">Đang tải dữ liệu…</div>:rows.length===0?<div className="empty"><span>◇</span><h3>{query||filter?'Không tìm thấy kết quả':'Chưa có dữ liệu'}</h3><p>{tab==='products'&&!data.categories.length?'Tạo danh mục trước khi thêm sản phẩm.':'Thêm dữ liệu mới hoặc thay đổi bộ lọc để tiếp tục.'}</p></div>:<div className="table-scroll"><table><thead><tr>{(tab==='courts'?['Số sân','Tên sân','Loại sân','Trạng thái','Giá gốc / khung giờ']:tab==='time-slots'?['Khung giờ','Hệ số giá','Phân loại']:tab==='categories'?['Tên danh mục','Số sản phẩm']:['Tên sản phẩm','Danh mục','Loại','Đơn vị','Giá niêm yết','Tồn kho']).map(h=><th key={h}>{h}</th>)}<th>Thao tác</th></tr></thead><tbody>{rows.map(x=><tr key={x.id}>{tab==='courts'?<><td>#{String(x.courtNumber).padStart(2,'0')}</td><td><strong>{x.name}</strong><p className='court-location'>{x.district||'Chưa cập nhật quận / huyện'}</p><p className='court-address'>Địa chỉ: {x.address||'Chưa cập nhật địa chỉ'}</p></td><td>{labels[x.type]||'Chưa xác minh'}</td><td><span className={'badge '+x.status}>{labels[x.status]}</span></td><td>{money(x.basePrice)}</td></>:tab==='time-slots'?<><td><strong>{x.startTime.slice(0,5)} — {x.endTime.slice(0,5)}</strong></td><td><span className={'badge '+(x.priceMultiplier>1?'MAINTENANCE':'AVAILABLE')}>×{Number(x.priceMultiplier).toFixed(2)}</span></td><td>{x.priceMultiplier>1?'Giá cao điểm':'Giá thông thường'}</td></>:tab==='categories'?<><td><strong>{x.categoryName}</strong></td><td>{data.products.filter(p=>p.categoryId===x.id).length}</td></>:<><td><strong>{x.name}</strong></td><td>{data.categories.find(c=>c.id===x.categoryId)?.categoryName||'—'}</td><td><span className="badge neutral">{labels[x.type]}</span></td><td>{x.unit}</td><td>{money(x.price)}</td><td>{x.type==='SERVICE'?'—':x.stockQuantity}</td></>}<td><div className="actions"><button onClick={()=>open(x)}>Sửa</button><button className="danger" onClick={()=>{setError('');setDeleteTarget(x)}}>Xóa</button></div></td></tr>)}</tbody></table></div>}


 </section>
 {tab==='time-slots'&&<section className="price-panel"><div><span className="eyebrow">KIỂM TRA BẢNG GIÁ</span><h3>Giá sân theo khung giờ</h3><p className="muted">Giá gốc × hệ số. Khung giờ có thể cấu hình riêng.</p></div><div className="quote-controls"><select aria-label="Chọn sân tính giá" value={quoteCourt} onChange={e=>{setQuoteCourt(e.target.value);setQuote(null)}}><option value="">Chọn sân hoạt động</option>{data.courts.filter(c=>c.status==='AVAILABLE').map(c=><option value={c.id} key={c.id}>{c.name}</option>)}</select><select aria-label="Chọn khung giờ tính giá" value={quote?.timeSlotId||''} onChange={async e=>{setError('');const id=e.target.value;if(!id){setQuote(null);return}try{setQuote(await api('/public/price-quote?courtId='+quoteCourt+'&timeSlotId='+id))}catch(err){setError(err.message)}}} disabled={!quoteCourt}><option value="">Chọn khung giờ</option>{data['time-slots'].map(s=><option key={s.id} value={s.id}>{s.startTime.slice(0,5)} – {s.endTime.slice(0,5)}</option>)}</select>{quote&&<strong className="quote-value">{money(quote.basePrice)} × {quote.priceMultiplier} = {money(quote.price)}</strong>}</div></section>}
 </>}

 {/* POS PANEL */}
 {tab==='pos'&&<div><section className="content-panel" style={{padding:20,marginBottom:20}}><label className="field">Mã sân để mua hàng<input aria-label="Mã sân" value={posBookingId} onChange={e=>setPosBookingId(e.target.value.trim())}/></label>{counterReceipt&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true"><h2>Xác nhận đã nhận tiền sân</h2>{error&&<p role="alert" className="alert error">{error}</p>}<p>Chỉ xác nhận khi đã nhận đủ tiền sau ưu đãi. Hệ thống ghi nhận người xác nhận; không dùng cho giao dịch online đang xử lý.</p><button disabled={saving} onClick={()=>setCounterReceipt(false)}>Hủy</button>{['CASH','BANK_TRANSFER'].map(method=><button key={method} className="primary" disabled={saving} onClick={async()=>{setSaving(true);try{const amount=await api('/pos/bookings/'+posBookingId+'/receive','POST',{paymentMethod:method});setNotice('Đã ghi nhận tiền sân: '+money(amount));setCounterReceipt(false)}catch(e){setError(e.message)}finally{setSaving(false)}}}>{method==='CASH'?'Đã nhận tiền mặt':'Đã xác nhận chuyển khoản'}</button>)}</section></div>}

</section>
  <div className="page-heading"><div><span className="eyebrow">OPERATIONS</span><h1>Bán hàng tại quầy (POS)</h1><p className="muted">Lập đơn bán nhanh cho khách hàng và thu tiền tại quầy.</p></div></div>
  <div className="pos-layout">
   <div className="pos-catalog">
    <div className="toolbar" style={{padding:'0 0 16px',borderBottom:'1px solid #edf0e8'}}>
     <label className="search" style={{width:'100%'}}><span>⌕</span><input placeholder="Tìm mặt hàng hoặc dịch vụ…" value={posSearch} onChange={e=>setPosSearch(e.target.value)}/></label>
    </div>
    <div className="pos-product-grid">
     {data.products.filter(p=>p.name.toLocaleLowerCase('vi').includes(posSearch.toLocaleLowerCase('vi'))).map(p=><div className="pos-product-card" key={p.id}>
      <div><h4>{p.name}</h4><p>{labels[p.type]||p.type} · Đơn vị: {p.unit}</p><p>{p.type==='SERVICE'?'Dịch vụ không quản tồn':'Tồn kho: '+(p.stockQuantity??0)}</p></div>
      <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',marginTop:'10px'}}>
       <span className="price">{money(p.price)}</span>
       <button className="primary" style={{padding:'7px 12px',fontSize:'11px'}} onClick={()=>addToCart(p)}>Thêm +</button>
      </div>
     </div>)}
    </div>
   </div>
   <div className="pos-cart-panel">
    <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}><h3>Đơn hàng hiện tại</h3><button className="secondary" style={{padding:'5px 10px',fontSize:'11px'}} onClick={()=>setPosCart([])} disabled={!posCart.length}>Xóa trắng</button></div>
    <div className="pos-cart-items">
     {!posCart.length?<p className="muted" style={{textAlign:'center',padding:'30px 0'}}>Chưa có sản phẩm trong đơn.</p>:posCart.map(item=><div className="pos-cart-item" key={item.id}>
      <div style={{flex:1}}><strong>{item.name}</strong><br/><small className="muted">{money(posQuote?.lines.find(l=>l.productId===item.id)?.unitPrice??item.price)} × {item.quantity}</small>{posQuote?.lines.find(l=>l.productId===item.id)?.promotionName&&<small className="promotion-applied">{posQuote.lines.find(l=>l.productId===item.id).promotionName}: −{money(posQuote.lines.find(l=>l.productId===item.id).discountAmount)}</small>}</div>
      <div className="pos-stepper">
       <button onClick={()=>updateCartQuantity(item.id,-1)}>−</button>
       <span>{item.quantity}</span>
       <button onClick={()=>updateCartQuantity(item.id,1)}>+</button>
       <button onClick={()=>removeFromCart(item.id)} style={{color:'#ad5743',marginLeft:'4px'}}>✕</button>
      </div>
     </div>)}
    </div>
    <div className="pos-summary">
     <div className="pos-summary-line"><span>Số lượng:</span><strong>{posCart.reduce((s,i)=>s+i.quantity,0)}</strong></div>
     <div className="pos-summary-line"><span>Tiền sản phẩm:</span><strong>{posQuote?money(posQuote.productFee):'—'}</strong></div><div className="pos-summary-line"><span>Khuyến mãi:</span><strong>{posQuote?'−'+money(posQuote.discountAmount):'—'}</strong></div><div className="pos-summary-total"><span>Tổng thanh toán:</span><span>{posQuoteBusy?'Đang tính…':posQuote?money(posQuote.totalAmount):'—'}</span></div>{posQuoteError&&<div className="alert error" role="alert">{posQuoteError}</div>}


     <label className="field" style={{marginTop:'14px'}}>Khách hàng<select value={posCustomerId} onChange={e=>setPosCustomerId(e.target.value)}><option value="">Khách vãng lai (Mặc định)</option>{customers.map(c=><option key={c.id} value={c.id}>{c.fullName} ({c.phone})</option>)}</select></label>
     <button className="primary wide" style={{marginTop:'14px'}} disabled={!posCart.length||saving||posQuoteBusy||!posQuote||!!posQuoteError} onClick={handlePosSale}>{saving?'Đang xử lý…':posBookingId?'Thêm hàng vào booking':'✓ Thu tiền ngay (CASH)'}</button>
    </div>
   </div>
  </div>
  {posHistory.length>0&&<div className="content-panel" style={{marginTop:'24px'}}><div style={{padding:'16px 20px',borderBottom:'1px solid #edf0e8'}}><h3>Giao dịch POS gần đây</h3></div><div className="table-scroll"><table><thead><tr><th>Mã giao dịch</th><th>Thời gian</th><th>Số món</th><th>Khuyến mãi</th><th>Tổng tiền</th><th>Trạng thái</th></tr></thead><tbody>{posHistory.map(h=><tr key={h.id}><td><strong>{h.id}</strong></td><td>{h.time}</td><td>{h.items.length} món</td><td>{money(h.discount||0)}</td><td><strong>{money(h.total)}</strong></td><td><span className={"badge "+(h.status==='DRAFT'?'MAINTENANCE':'AVAILABLE')}>{h.status==='DRAFT'?'Chờ kết toán':'Đã thanh toán'}</span></td></tr>)}</tbody></table></div></div>}


 </div>}



 {/* ACTIVE BOOKINGS PANEL */}
{tab==='active-bookings' && (
  <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">OPERATIONS</span>
        <h1>Sân đã đặt</h1>
        <p className="muted">Danh sách các sân đang có booking chưa thanh toán.</p>
      </div>
    </div>
    <section className="content-panel">
      <div className="toolbar">
        <label className="search"><span>⌕</span>
          <input aria-label="Tìm kiếm" placeholder="Tìm kiếm…" value={query} onChange={e=>setQuery(e.target.value)} />
        </label>
        <span className="count">{activeBookings.length} kết quả</span>
        <button className="secondary" onClick={loadOperationalData} disabled={activeBusy}>↻ Làm mới</button>
      </div>
      {activeBusy ? (
        <div className="empty" role="status">Đang tải dữ liệu…</div>
      ) : activeError ? (
        <div className="alert error" role="alert">{activeError}</div>
      ) : activeBookings.length===0 ? (
        <div className="empty"><span>◇</span><h3>Chưa có sân đã đặt</h3></div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead><tr><th>UUID</th><th>Sân</th><th>Khách</th><th>Thời gian</th><th>Tổng tiền</th><th>Thao tác</th></tr></thead>
            <tbody>
              {activeBookings.map((b,i) => ( console.log('Booking item:', b),
                <tr key={b.bookingId ?? i}>
                  <td>{b.shortId ?? '—'}</td>
                  <td>{b.courtDetails?.split('·')[0]?.trim() ?? '—'}</td>
                  <td>{b.customerName||'—'} ({b.customerPhone||'—'})</td>
                  <td>{b.courtDetails?.match(/\(([^)]+)\)/)?.[1] ?? '—'}</td>
                  <td>{money(b.courtFee ?? b.court_fee ?? b.totalAmount ?? b.amountDue ?? 0)}</td>
                  <td><button className="primary" onClick={()=>{setCheckoutBookingId(b.id); setTab('checkout');}}>Kết toán</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  </>
)}
{/* CHECKOUT PANEL */}
 {tab==='checkout'&&<div>
  <div className="page-heading"><div><span className="eyebrow">OPERATIONS</span><h1>Check-out & Kết toán trả sân</h1><p className="muted">Tính tổng tiền giờ sân cùng các dịch vụ đã dùng, kết toán hóa đơn.</p></div></div>
  <div className="checkout-layout">
   <div className="invoice-paper">
    <h3>Lập phiếu kết toán</h3>
    <div className="form-grid" style={{marginBottom:'20px'}}>
     <label className="field">Mã Booking (UUID)<input placeholder="Nhập UUID booking cần kết toán" value={checkoutBookingId} onChange={e=>setCheckoutBookingId(e.target.value)} required/></label>
    </div>
    <button className="primary" onClick={createCheckoutSlip}>Lấy bill từ hệ thống</button>

    {checkoutSlip&&<div style={{marginTop:'28px',paddingTop:'24px',borderTop:'1px solid #edf0e8'}}>
     <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
      <div><span className="eyebrow">PHIẾU TẠM TÍNH</span><h2>Phiếu #{checkoutSlip.id}</h2><p className="muted">Booking: <strong>{checkoutSlip.bookingId}</strong></p></div>
      <span className={'badge '+(checkoutSlip.status==='PAID'?'AVAILABLE':'MAINTENANCE')}>{checkoutSlip.status==='PAID'?'Đã thanh toán':'Chờ thanh toán'}</span>
     </div>
     <div className="invoice-row"><span>Tiền thuê sân (court_fee):</span><strong>{money(checkoutSlip.courtFee)}</strong></div>
     <div className="invoice-row"><span>Tiền hàng gốc (product_fee):</span><strong>{money(checkoutSlip.productFee)}</strong></div>
     <div className="invoice-row"><span>Giảm tiền sân:</span><strong>{money(checkoutSlip.courtDiscount||0)}</strong></div>
     <div className="invoice-row"><span>Giảm sản phẩm:</span><strong>{money(checkoutSlip.productDiscount||0)}</strong></div>
     <div className="invoice-row"><span>Tiền sân đã thanh toán:</span><strong>{money(checkoutSlip.paidCourtAmount||0)}</strong></div>
     <div className="invoice-row"><span>Còn phải thu:</span><strong>{money(checkoutSlip.amountDue||0)}</strong></div>
     <div className="invoice-total-block">
      <div className="total-line"><span>TỔNG BILL SAU GIẢM:</span><span>{money(checkoutSlip.totalAmount)}</span></div>
     </div>
    </div>}


   </div>

   <div className="pos-cart-panel">
    <h3>Xác nhận & Thu tiền</h3>
    <p className="muted">Chọn phương thức thanh toán chuẩn:</p>
    <div className="payment-picker">
     <button className={'payment-btn '+(checkoutPaymentMethod==='CASH'?'selected':'')} onClick={()=>setCheckoutPaymentMethod('CASH')}>
      <span>💵 Tiền mặt (CASH)</span>{checkoutPaymentMethod==='CASH'&&<span>✓</span>}
     </button>
     <button className={'payment-btn '+(checkoutPaymentMethod==='BANK_TRANSFER'?'selected':'')} onClick={()=>setCheckoutPaymentMethod('BANK_TRANSFER')}>
      <span>🏦 Chuyển khoản / QR (BANK_TRANSFER)</span>{checkoutPaymentMethod==='BANK_TRANSFER'&&<span>✓</span>}
     </button>
     <button className={'payment-btn '+(checkoutPaymentMethod==='VNPAY'?'selected':'')} onClick={()=>setCheckoutPaymentMethod('VNPAY')}>
      <span>💳 VNPay (VNPAY)</span>{checkoutPaymentMethod==='VNPAY'&&<span>✓</span>}
     </button>
    </div>
    <button className="primary wide" disabled={!checkoutSlip||checkoutSlip.status==='PAID'||saving} onClick={confirmCheckout}>{saving?'Đang xử lý…':'✓ Xác nhận & thu tiền'}</button>
   </div>
  </div>

  {checkoutHistory.length>0&&<div className="content-panel" style={{marginTop:'24px'}}><div style={{padding:'16px 20px',borderBottom:'1px solid #edf0e8'}}><h3>Lịch sử trả sân</h3></div><div className="table-scroll"><table><thead><tr><th>Mã phiếu</th><th>Booking ID</th><th>Tiền sân</th><th>Tiền dịch vụ</th><th>Tổng thu</th><th>Phương thức</th><th>Trạng thái</th></tr></thead><tbody>{checkoutHistory.map(h=><tr key={h.id}><td><strong>{h.id}</strong></td><td>{h.bookingId}</td><td>{money(h.courtFee)}</td><td>{money(h.productFee)}</td><td><strong>{money(h.totalAmount)}</strong></td><td><span className="badge neutral">{h.paymentMethod}</span></td><td><span className="badge AVAILABLE">Đã thanh toán</span></td></tr>)}</tbody></table></div></div>}


 </div>}



 {/* INVENTORY PANEL */}
 {tab==='inventory'&&<div>
  <div className="page-heading"><div><span className="eyebrow">INVENTORY</span><h1>Quản lý Nhập kho & Nhà cung cấp</h1><p className="muted">Tạo và duyệt các phiếu nhập kho từ nhà cung cấp để tăng tồn kho.</p></div><button className="primary" onClick={()=>setImportModalOpen(true)}>＋ Lập phiếu nhập</button></div>
  <div className="stats">
   <div><span>Phiếu chờ duyệt</span><strong>{importOrders.filter(o=>o.status!=='RECEIVED').length.toString().padStart(2,'0')}</strong><small className="green">Cần xác nhận</small></div>
   <div><span>Phiếu đã nhập kho</span><strong>{importOrders.filter(o=>o.status==='RECEIVED').length.toString().padStart(2,'0')}</strong><small>Đã cộng tồn kho</small></div>
   <div><span>Nhà cung cấp</span><strong>{suppliers.length.toString().padStart(2,'0')}</strong><small>Đối tác</small></div>
   <div><span>Tổng giá trị nhập</span><strong>{money(importOrders.filter(o=>o.status==='RECEIVED').reduce((s,o)=>s+Number(o.totalAmount||0),0))}</strong><small>Đã duyệt</small></div>
  </div>
  <section className="content-panel">
   <div className="table-scroll"><table><thead><tr><th>Mã phiếu</th><th>Ngày nhập</th><th>Tổng tiền</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>
    {!importOrders.length?<tr><td colSpan="5" style={{textAlign:'center',padding:'30px'}}>Chưa có phiếu nhập nào. Bấm '＋ Lập phiếu nhập' để tạo mới.</td></tr>:importOrders.map(o=><tr key={o.id}>
     <td><strong>{o.id}</strong></td>
     <td>{o.importDate}</td>
     <td><strong>{money(o.totalAmount)}</strong></td>
     <td><span className={'badge '+(o.status==='RECEIVED'?'AVAILABLE':'MAINTENANCE')}>{o.status==='RECEIVED'?'Đã nhập kho':'Chờ duyệt'}</span></td>
     <td>{o.status!=='RECEIVED'&&<button className="approve-button" onClick={()=>handleApproveImportOrder(o.id)} disabled={saving}>✓ Duyệt nhập kho</button>}</td>
    </tr>)}
   </tbody></table></div>
  </section>

  {importModalOpen&&<div className="overlay"><section className="dialog" role="dialog"><div className="dialog-head"><h2>Lập phiếu nhập hàng</h2><button onClick={()=>setImportModalOpen(false)}>✕</button></div><form onSubmit={handleCreateImportOrder}><div className="form-grid">
   <label className="field">Nhà cung cấp<select value={importForm.supplierId} onChange={e=>setImportForm({...importForm,supplierId:e.target.value})} required>{suppliers.map(s=><option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
   <label className="field">Sản phẩm nhập<select value={importForm.productId} onChange={e=>setImportForm({...importForm,productId:e.target.value})} required>{data.products.filter(p=>p.type==='GOODS').map(p=><option key={p.id} value={p.id}>{p.name} ({p.unit})</option>)}</select></label>
   <label className="field">Số lượng nhập<input type="number" min="1" value={importForm.quantity} onChange={e=>setImportForm({...importForm,quantity:e.target.value})} required/></label>
   <label className="field">Đơn giá nhập (VNĐ)<input type="number" min="0" value={importForm.importPrice} onChange={e=>setImportForm({...importForm,importPrice:e.target.value})} required/></label>
  </div><div className="dialog-foot"><button type="button" className="secondary" onClick={()=>setImportModalOpen(false)}>Hủy</button><button className="primary" disabled={saving}>{saving?'Đang tạo…':'Lập phiếu nhập'}</button></div></form></section></div>}


 </div>}



 {/* ANALYTICS PANEL */}
 {tab==='analytics'&&<div>
  <div className="page-heading">
   <div><span className="eyebrow">ANALYTICS</span><h1>Doanh thu & Báo cáo</h1><p className="muted">Báo cáo tổng hợp tiền sân (court_fee) và tiền dịch vụ (product_fee).</p></div>
   <div style={{display:'flex',gap:'10px'}}>
    {[['DAY','Hôm nay'],['WEEK','Tuần này'],['MONTH','Tháng này']].map(([p,l])=><button key={p} className={analyticsPeriod===p?'primary':'secondary'} onClick={()=>setAnalyticsPeriod(p)}>{l}</button>)}
   </div>
  </div>
  <div className="stats">
   <div><span>Doanh thu sau giảm giá</span><strong>{money(revenueData?.total_amount||revenueData?.totalAmount||0)}</strong><small className="green">Kỳ hiện tại ({analyticsPeriod})</small></div>
   <div><span>Tiền sân (court_fee)</span><strong>{money(revenueData?.court_fee||revenueData?.courtFee||0)}</strong><small>Từ lượt đặt sân</small></div>
   <div><span>Hàng hóa & DV (product_fee)</span><strong>{money(revenueData?.product_fee||revenueData?.productFee||0)}</strong><small>Từ bán lẻ & phụ phí</small></div>
   <div><span>Thời gian ghi nhận</span><strong>{revenueData?.from||'—'}</strong><small>Đến {revenueData?.to||'—'}</small></div>
  </div>
 </div>}



 </div><footer>BCM · Master Data & Operations Desk <span>Sân cầu · Khung giờ · Danh mục · Hàng hóa · POS · Trả sân · Kho</span></footer></main>

 {modal&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-head"><h2 id="dialog-title">{modal.id?'Chỉnh sửa':'Thêm mới'} · {title}</h2><button aria-label="Đóng" disabled={saving} onClick={()=>{setModal(null);setError('')}}>✕</button></div><form onSubmit={save}><div className="form-grid">{tab==='courts'?<>{field('courtNumber','Số sân','number',{min:1,step:1})}{field('name','Tên sân','text',{maxLength:255})}{field('district','Quận / Huyện','text',{maxLength:150,required:false})}{field('address','Địa chỉ sân','text',{maxLength:500,required:false})}<p className="form-help">Địa chỉ tối đa 500 ký tự, thống nhất với quận / huyện. Để trống địa chỉ khi sửa sẽ giữ dữ liệu đang lưu.</p>{select('type','Loại sân',['STANDARD_MAT'].map(v=>[v,labels[v]]))}{select('status','Trạng thái',['AVAILABLE','MAINTENANCE','CLOSED'].map(v=>[v,labels[v]]))}{field('basePrice','Giá gốc (VNĐ / khung giờ)','number',{min:0,max:99999999.99,step:0.01})}</>:tab==='time-slots'?<>{field('startTime','Giờ bắt đầu','time')}{field('endTime','Giờ kết thúc','time')}{field('priceMultiplier','Hệ số giá','number',{min:0.01,max:9.99,step:0.01})}<p className="form-help">Mặc định 17:00–21:00 ×1.5, giờ khác ×1.0. Có thể điều chỉnh hệ số riêng. Khung giờ không được chồng lấn.</p></>:tab==='categories'?field('categoryName','Tên danh mục','text',{maxLength:255}):<>{field('name','Tên hàng hóa / dịch vụ','text',{maxLength:255})}{select('categoryId','Danh mục',data.categories.map(c=>[c.id,c.categoryName]))}{select('type','Loại',['GOODS','SERVICE'].map(v=>[v,labels[v]]))}{field('unit','Đơn vị tính','text',{maxLength:50})}{field('price','Giá niêm yết (VNĐ)','number',{min:0,max:99999999.99,step:0.01})}{field('stockQuantity',modal.id?'Tồn kho hiện tại':'Tồn ban đầu','number',{min:0,max:2147483647,step:1,disabled:!!modal.id||form.type==='SERVICE'})}<p className="form-help">Tồn ban đầu chỉ nhập khi tạo sản phẩm. Dịch vụ không theo dõi tồn kho. Điều chỉnh tồn sau này thuộc phân hệ kho.</p></>}</div>{error&&<div className="alert error" role="alert">{error}</div>}

<div className="dialog-foot"><button type="button" className="secondary" disabled={saving} onClick={()=>{setModal(null);setError('')}}>Hủy</button><button className="primary" disabled={saving}>{saving?'Đang lưu…':'Lưu thay đổi'}</button></div></form></section></div>}


 {deleteTarget&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="delete-title"><h2 id="delete-title">Xóa dữ liệu?</h2><p>Bạn muốn xóa <strong>{deleteTarget.name||deleteTarget.categoryName||deleteTarget.startTime+' – '+deleteTarget.endTime}</strong> khỏi danh sách?</p><p className="muted">Hệ thống sẽ kiểm tra các dữ liệu liên quan trước khi thực hiện.</p>{error&&<div className="alert error" role="alert">{error}</div>}

<div className="dialog-foot"><button className="secondary" disabled={saving} onClick={()=>{setDeleteTarget(null);setError('')}}>Hủy</button><button className="primary delete" disabled={saving} onClick={remove}>{saving?'Đang xóa…':'Xác nhận xóa'}</button></div></section></div>}


 </div>;
}
