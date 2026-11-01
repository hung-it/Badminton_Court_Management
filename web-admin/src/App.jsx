import React,{useState,useEffect} from 'react';
import ProductPromotions from './Promotions.jsx';
import CourtBrowser from './CourtBrowser.jsx';
import TimeSlotFilters from './TimeSlotFilters.jsx';
import RevenueInvoices from './RevenueInvoices.jsx';
import Statistics from './Statistics.jsx';
import PeriodFilter,{inPeriod} from './PeriodFilter.jsx';
const money=n=>new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND'}).format(n||0);
const labels={STANDARD_MAT:'Thảm tiêu chuẩn',WOODEN_FLOOR:'Sàn gỗ',AVAILABLE:'Hoạt động',MAINTENANCE:'Bảo trì',CLOSED:'Đóng cửa',GOODS:'Hàng hóa',SERVICE:'Dịch vụ'};
const masterTabs=[['courts','Sân cầu'],['time-slots','Khung giờ & giá'],['categories','Danh mục'],['products','Hàng hóa & dịch vụ'],['product-promotions','Khuyến mãi']];
const opTabs=[['pos','Bán tại quầy'],['checkout','Trả sân'],['inventory','Nhập kho'],['analytics','Doanh thu'],['statistics','Thống kê']];
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
 const [credentials,setCredentials]=useState({email:'',password:''}),[quoteCourt,setQuoteCourt]=useState('');
 const [priceBranches,setPriceBranches]=useState([]);
 const [branchCount,setBranchCount]=useState(0),[slotPrices,setSlotPrices]=useState({}),[priceError,setPriceError]=useState('');

 // POS state
 const [posCart,setPosCart]=useState([]);
 const [posQuote,setPosQuote]=useState(null),[posQuoteBusy,setPosQuoteBusy]=useState(false),[posQuoteError,setPosQuoteError]=useState('');
 const [posSearch,setPosSearch]=useState('');
 const [posHistory,setPosHistory]=useState([]);
 const [posMode,setPosMode]=useState('booking');
 const [posPromotionCode,setPosPromotionCode]=useState('');
 const [posBookingPreview,setPosBookingPreview]=useState(null);

 // Checkout state
 const [checkoutBookingId,setCheckoutBookingId]=useState('');
 const [posBookingId,setPosBookingId]=useState('');
 const [checkoutPaymentMethod,setCheckoutPaymentMethod]=useState('CASH');
 const [checkoutSlip,setCheckoutSlip]=useState(null);
 const [checkoutOpen,setCheckoutOpen]=useState(false);
 const [courtReceiptConfirmed,setCourtReceiptConfirmed]=useState(false);
 useEffect(()=>setCourtReceiptConfirmed(false),[checkoutSlip]);
 const [checkoutHistory,setCheckoutHistory]=useState([]);
  const [activeBookings,setActiveBookings]=useState([]);
  const [activeBusy,setActiveBusy]=useState(false);
  const [activeError,setActiveError]=useState('');

 // Inventory state
 const [importOrders,setImportOrders]=useState([]);
 const [inventoryPeriod,setInventoryPeriod]=useState('DAY'),[inventoryMonth,setInventoryMonth]=useState('');
 const [inventoryRange,setInventoryRange]=useState({});
 const visibleImportOrders=importOrders.filter(o=>inPeriod(o.importDate,inventoryPeriod,inventoryMonth,undefined,inventoryRange));
 const [suppliers,setSuppliers]=useState([]);
 const [importModalOpen,setImportModalOpen]=useState(false);
 const [importForm,setImportForm]=useState({supplierName:'',productName:'',quantity:'',totalAmount:''});
 const [selectedImport,setSelectedImport]=useState(null);
 const [priceApprovalTarget,setPriceApprovalTarget]=useState(null),[approvalPrice,setApprovalPrice]=useState('');

 function isImportProduct(product){return product.priceApprovalManaged||data.categories.some(c=>c.id===product.categoryId&&c.categoryName.trim().toLocaleLowerCase('vi')==='hàng nhập kho');}
 async function approveProductPrice(e){
  e.preventDefault();setSaving(true);setError('');
  try{await api('/admin/products/'+priceApprovalTarget.id+'/approve-price','POST',{price:approvalPrice,version:priceApprovalTarget.version});setPriceApprovalTarget(null);setNotice('Đã duyệt giá niêm yết. Sản phẩm được phép bán tại quầy.');await load();}
  catch(e){setError(e.message);}finally{setSaving(false);}
 }

 // Analytics state
 const [analyticsPeriod,setAnalyticsPeriod]=useState('DAY');
 const [analyticsMonth,setAnalyticsMonth]=useState('');
 const [analyticsRange,setAnalyticsRange]=useState({});
 const analyticsDate=analyticsMonth?analyticsMonth+'-01':'';
 const analyticsParams=analyticsRange.from?'from='+analyticsRange.from+'&to='+analyticsRange.to:'period='+analyticsPeriod+(analyticsDate?'&date='+analyticsDate:'');
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
   const [values,branches]=await Promise.all([Promise.all(masterTabs.map(([key])=>api('/admin/'+key))),api('/public/branches')]);
   setPriceBranches(branches);
   setBranchCount(new Set([...branches.map(b=>b.id),...values[0].map(c=>c.branchId).filter(Boolean)]).size);
   setData(Object.fromEntries(masterTabs.map(([key],i)=>[key,values[i]])));
  }catch(e){setError(e.message)}finally{setBusy(false)}
 }

 async function loadOperationalData(){
  try{
   if(tab==='inventory'){
    const [orders,supps]=await Promise.all([api('/import-orders'),api('/suppliers')]);
    setImportOrders(orders||[]);setSuppliers(supps||[]);
   }else if(tab==='checkout'){
        setActiveBusy(true);
        setActiveError('');
        try{
          const data=await api('/checkout/active-bookings');
          setActiveBookings(data||[]);
        }catch(e){setActiveError(e.message);}
        finally{setActiveBusy(false);}
   }
  }catch(err){if(tab==='inventory')setError(err.message);else console.error(err);}
 }

 useEffect(()=>{if(session)load()},[session]);
 useEffect(()=>{
  if(!session||tab!=='analytics')return;
  let active=true;setRevenueData(null);
  api('/analytics/revenue?'+analyticsParams).then(v=>{if(active)setRevenueData(v)}).catch(e=>{if(active)setError(e.message)});
  return()=>{active=false};
 },[session,tab,analyticsParams]);
 useEffect(()=>{
  setQuery('');setFilter('');setNotice('');
  if(session)loadOperationalData();
 },[tab]);

 useEffect(()=>{
  let active=true;setSlotPrices({});setPriceError('');
  if(tab!=='time-slots'||!quoteCourt)return;
  Promise.all(data['time-slots'].map(async slot=>[slot.id,await api('/public/price-quote?courtId='+quoteCourt+'&timeSlotId='+slot.id)]))
   .then(entries=>{if(active)setSlotPrices(Object.fromEntries(entries))}).catch(e=>{if(active)setPriceError(e.message)});
  return()=>{active=false};
 },[tab,quoteCourt,data]);
 async function login(e){e.preventDefault();setSaving(true);setError('');try{const s=await api('/auth/login','POST',credentials,null);if(!s.user?.roles?.some(r=>['ADMIN','STAFF'].includes(r)))throw new Error('Tài khoản cần quyền ADMIN hoặc STAFF.');setTab(s.user.roles.includes('ADMIN')?'courts':'pos');sessionStorage.setItem('bcm-session',JSON.stringify(s));setSession(s);setCredentials({email:'',password:''})}catch(e){setError(e.message)}finally{setSaving(false)}}
 function open(item=null){setModal({id:item?.id||null});setForm(item?{...item,...(tab==='courts'?{courtNumber:item.courtCode??item.courtNumber,type:'STANDARD_MAT'}:{})}:{...initial[tab],...(tab==='courts'?{courtNumber:Math.max(0,...data.courts.map(c=>c.courtCode??c.courtNumber))+1}:{}),...(tab==='products'?{categoryId:data.categories[0]?.id||''}:{})});setFieldErrors({});setError('');}
 function change(key,value){setForm(old=>{const next={...old,[key]:value};if(tab==='products'&&key==='type'&&value==='SERVICE')next.stockQuantity=0;if(tab==='time-slots'&&(key==='startTime'||key==='endTime'))next.priceMultiplier=defaultMultiplier(next.startTime.slice(0,5),next.endTime.slice(0,5));return next});}
 async function save(e){e.preventDefault();setSaving(true);setFieldErrors({});setError('');try{let payload={...form};if(tab==='courts')validateCourtLocation(payload);for(const key of ['courtNumber','basePrice','priceMultiplier','price','stockQuantity'])if(key in payload)payload[key]=Number(payload[key]);await api('/admin/'+tab+(modal.id?'/'+modal.id:''),modal.id?'PUT':'POST',payload);setModal(null);setNotice('Đã lưu dữ liệu thành công.');await load()}catch(e){setError(e.message);if(e.fields&&typeof e.fields==='object')setFieldErrors(e.fields)}finally{setSaving(false)}}
 async function remove(){setSaving(true);setError('');try{await api('/admin/'+tab+'/'+deleteTarget.id,'DELETE');setDeleteTarget(null);setNotice('Đã xóa dữ liệu thành công.');await load()}catch(e){setError(e.message)}finally{setSaving(false)}}

useEffect(()=>{
 let active=true;
 if(!session||tab!=='pos'||!posCart.length){setPosQuote(null);setPosQuoteBusy(false);setPosQuoteError('');return;}
 setPosQuote(null);setPosBookingPreview(null);setPosQuoteBusy(true);setPosQuoteError('');
 const timer=setTimeout(async()=>{try{
  const bookingId=posMode==='booking'?posBookingId.trim():null;
  if(posMode==='booking'&&!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(bookingId))throw new Error('Vui lòng nhập mã đơn đặt sân (UUID) hợp lệ.');
  const [quote,preview]=await Promise.all([api('/pos/quote','POST',{bookingId,promotionCode:posPromotionCode.trim()||null,paymentMethod:'CASH',items:posCart.map(i=>({productId:i.id,quantity:i.quantity}))}),bookingId?api('/checkout/'+bookingId):Promise.resolve(null)]);
  if(active){setPosQuote(quote);setPosBookingPreview(preview);}
 }catch(e){if(active)setPosQuoteError(e.message)}finally{if(active)setPosQuoteBusy(false)}},150);
 return ()=>{active=false;clearTimeout(timer)};
},[session,tab,posCart,posMode,posBookingId,posPromotionCode]);
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
   const bookingId=posMode==='booking'?posBookingId.trim():null;
   if(posMode==='booking'&&(!bookingId||!posBookingPreview))throw new Error('Vui lòng kiểm tra mã đơn đặt sân trước khi thêm hàng.');
   const payload={
    customerId:null,
    bookingId,
    promotionCode:posPromotionCode.trim()||null,
    paymentMethod:'CASH',
    courtFee:0,
    items:posCart.map(i=>({productId:i.id,quantity:i.quantity}))
   };
   const inv=await api('/pos/sales','POST',payload);
   const totalAmt=inv.total_amount??inv.totalAmount;
   const discountAmt=inv.discountAmount??inv.discount_amount??0;
   setPosHistory(h=>[{id:inv.id||`#HD-${Date.now().toString().slice(-4)}`,time:new Date().toLocaleTimeString('vi-VN'),total:totalAmt,discount:discountAmt,status:inv.status,items:[...posCart]},...h]);
   setPosCart([]);
   setPosPromotionCode('');
   setNotice(bookingId?'Đã thêm hàng vào đơn đặt sân. Xem bill gộp và xác nhận phần tiền còn phải thu.':'Bán hàng thành công! Hóa đơn chỉ gồm hàng hóa, không kèm tiền sân.');
   if(bookingId){setCheckoutBookingId(bookingId);setCheckoutOpen(true);setCheckoutPaymentMethod('CASH');setCheckoutSlip(null);setTab('checkout');try{const v=await api('/checkout/'+bookingId);setCheckoutSlip({...v,id:'Xem trước',bookingId,courtFee:v.court_fee,productFee:v.product_fee,totalAmount:v.total_amount,status:'DRAFT'});}catch(e){setError('Đã lưu hàng vào đơn. Không tải được bill; hãy lập phiếu để tải lại. '+e.message);}}
   await load();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 // Checkout handlers
 async function createCheckoutSlip(id=checkoutBookingId){
  setCheckoutBookingId(id);setCheckoutOpen(true);setCheckoutSlip(null);setCheckoutPaymentMethod('CASH');setError('');setSaving(true);
  try{const v=await api('/checkout/'+id);setCheckoutSlip({...v,id:'Xem trước',bookingId:v.bookingId||id,courtFee:v.court_fee,productFee:v.product_fee,totalAmount:v.total_amount,status:'DRAFT'});}
  catch(e){setError(e.message)}finally{setSaving(false)}
 }
 async function confirmCheckout(){
  if(!checkoutSlip||checkoutSlip.status==='PAID')return;
  setSaving(true);setError('');
  try{
   const res=await api('/checkout/'+checkoutSlip.bookingId,'POST',{paymentMethod:checkoutPaymentMethod,courtReceiptConfirmed});
   const paidSlip={...checkoutSlip,...res,status:'PAID',paymentMethod:checkoutPaymentMethod,invoiceId:res.invoiceId,totalAmount:res.total_amount??res.totalAmount??checkoutSlip.totalAmount};
   setCheckoutSlip(paidSlip);
   setCheckoutHistory(h=>[paidSlip,...h]);
   setCheckoutOpen(false);
   setActiveBookings(rows=>rows.filter(b=>b.bookingId!==checkoutSlip.bookingId));
   setNotice('Kết toán thành công! Hóa đơn '+res.invoiceId+' gồm tiền sân và hàng tại quầy đã ghi nhận vào Doanh thu.');
   if(session.user?.roles?.includes('ADMIN'))setTab('analytics');
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 // Inventory handlers
 async function openImportDetail(order){
  setSelectedImport(order);setError('');setSaving(true);
  try{setSelectedImport(await api('/import-orders/'+order.id));}
  catch(e){setError(e.message);}finally{setSaving(false);}
 }
 async function handleCreateImportOrder(e){
  e.preventDefault();
  setSaving(true);setError('');
  try{
   if(!importForm.supplierName.trim()||!importForm.productName.trim())throw new Error('Vui lòng nhập nhà cung cấp và loại sản phẩm.');
   const payload={supplierName:importForm.supplierName.trim(),productName:importForm.productName.trim(),quantity:Number(importForm.quantity),totalAmount:importForm.totalAmount};
   await api('/import-orders/manual','POST',payload);
   setImportModalOpen(false);
   setNotice('Lập phiếu nhập thành công.');
   await load();
   await loadOperationalData();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }
 async function handleApproveImportOrder(orderId){
  setSaving(true);setError('');
  try{
   const approved=await api('/import-orders/'+orderId+'/approve','POST');
   setSelectedImport(approved);
   setNotice('Duyệt phiếu nhập thành công! Tồn kho đã được cập nhật.');
   await loadOperationalData();
   await load();
  }catch(e){setError(e.message);}finally{setSaving(false);}
 }

 const rows=data[tab]?data[tab].filter(x=>{const text=[x.name,x.categoryName,x.courtNumber,x.startTime,x.endTime,labels[x.type],labels[x.status]].join(' ').toLocaleLowerCase('vi');if(tab==='time-slots')return true;return text.includes(query.toLocaleLowerCase('vi'))&&(!filter||(tab==='products'?x.categoryId===filter:x.status===filter))}):[];
 const title=allTabs.find(([k])=>k===tab)?.[1]||'';
 const checkoutPaidCourt=Number(checkoutSlip?.paidCourtAmount||0)+(checkoutSlip?.courtReceiptRequired&&courtReceiptConfirmed?Number(checkoutSlip.courtFee||0)-Number(checkoutSlip.courtDiscount||0):0);
 const checkoutDue=Math.max(0,Number(checkoutSlip?.totalAmount||0)-checkoutPaidCourt);
 const awaitingCourtReceipt=!!checkoutSlip?.courtReceiptRequired&&!courtReceiptConfirmed;
 const filteredBookings=activeBookings.filter(b=>[b.bookingId,b.shortId,b.courtDetails,b.customerName,b.customerPhone].join(' ').toLocaleLowerCase('vi').includes(query.trim().toLocaleLowerCase('vi')));
 function field(key,label,type='text',extra={}){return <label className="field" key={key}>{label}<input aria-label={label} name={key} type={type} value={form[key]??''} onChange={e=>change(key,e.target.value)} required {...extra}/>{fieldErrors[key]&&<small className="invalid">{fieldErrors[key]}</small>}</label>}
 function select(key,label,options){return <label className="field" key={key}>{label}<select aria-label={label} name={key} value={form[key]??''} onChange={e=>change(key,e.target.value)} required>{options.map(([v,l])=><option key={v} value={v}>{l}</option>)}</select>{fieldErrors[key]&&<small className="invalid">{fieldErrors[key]}</small>}</label>}

 if(!session)return <div className="login-layout"><section className="login-story"><div className="brand">BCM<span>COURT MANAGEMENT</span></div><div><span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span><h1>Mọi sân chơi.<br/>Một nơi quản lý.</h1><p>Quản lý sân, khung giờ và danh mục hàng hóa<br/>để vận hành câu lạc bộ mỗi ngày.</p><div className="court-illustration"><i/><b/></div></div><small>BADMINTON COURT MANAGEMENT</small></section><section className="login-panel"><form onSubmit={login}><span className="eyebrow">CHÀO MỪNG TRỞ LẠI</span><h2>Đăng nhập quản trị</h2><p className="muted">Sử dụng tài khoản Admin hoặc Nhân viên của bạn.</p><label className="field">Email<input type="email" required autoComplete="username" value={credentials.email} onChange={e=>setCredentials({...credentials,email:e.target.value})}/></label><label className="field">Mật khẩu<input type="password" required autoComplete="current-password" value={credentials.password} onChange={e=>setCredentials({...credentials,password:e.target.value})}/></label>{error&&<div role="alert" className="alert error">{error}</div>}

<button className="primary wide" disabled={saving}>{saving?'Đang đăng nhập…':'Đăng nhập →'}</button></form></section></div>;

 return <div className="app-shell"><aside className="sidebar"><div className="brand">BCM<span>COURT MANAGEMENT</span></div><span className="nav-caption">QUẢN LÝ DỮ LIỆU</span><nav>{masterTabs.filter(([key])=>session.user?.roles?.includes('ADMIN')||key==='product-promotions').map(([key,label])=><button key={key} className={tab===key?'nav active':'nav'} onClick={()=>setTab(key)}>{label}</button>)}</nav><span className="tab-section-label">VẬN HÀNH & POS</span><nav>{opTabs.filter(([key])=>session.user?.roles?.includes('ADMIN')||['pos','checkout'].includes(key)).map(([key,label])=><button key={key} className={tab===key?'nav active':'nav'} onClick={()=>setTab(key)}>{label}</button>)}</nav><div className="sidebar-bottom"><div className="avatar">A</div><div><strong>{session.user?.fullName||'Quản trị viên'}</strong><small>{session.user?.roles?.includes('ADMIN')?'Administrator':'Nhân viên'}</small></div><button aria-label="Đăng xuất" title="Đăng xuất" onClick={logout}>↗</button></div></aside><main><header className="topbar"><span>Vận hành / <strong>{title}</strong></span><span className="live"><i/> Web Admin</span><button className="mobile-logout secondary" onClick={logout}>Đăng xuất</button></header><div className="workspace">

 {error&&!modal&&!deleteTarget&&<div role="alert" className="alert error">{error} <button onClick={load}>Thử lại</button></div>}

{notice&&<div role="status" className="alert success">{notice}</div>}



 {tab==='product-promotions'&&<ProductPromotions promotions={data['product-promotions']||[]} products={data.products} api={api} onChanged={load} canManage={session.user?.roles?.includes('ADMIN')}/>}
 {/* MASTER DATA TABS */}
 {['courts','time-slots','categories','products'].includes(tab)&&<>
 <div className="page-heading"><div><span className="eyebrow">MASTER DATA</span><h1>{title}</h1></div><button className="primary" onClick={()=>open()} disabled={busy||(tab==='products'&&!data.categories.length)}>＋ {tab==='courts'?'Thêm sân':tab==='time-slots'?'Thêm khung giờ':tab==='categories'?'Thêm danh mục':'Thêm sản phẩm'}</button></div>
 <div className={'stats'+(tab==='time-slots'?' three-stats':'')}><div><span>Tổng số sân</span><strong>{data.courts.length.toString().padStart(2,'0')}</strong><small>Trong hệ thống</small></div><div><span>Sân hoạt động</span><strong>{data.courts.filter(c=>c.status==='AVAILABLE').length.toString().padStart(2,'0')}</strong><small className="green">Sẵn sàng phục vụ</small></div><div><span>Khung giờ</span><strong>{data['time-slots'].length.toString().padStart(2,'0')}</strong><small>Cấu hình trong ngày</small></div>{!['courts','time-slots'].includes(tab)&&<div><span>Hàng hóa & dịch vụ</span><strong>{data.products.length.toString().padStart(2,'0')}</strong><small>{data.categories.length} danh mục</small></div>}{tab==='courts'&&<div><span>Chi nhánh</span><strong>{branchCount.toString().padStart(2,'0')}</strong><small>Trong hệ thống</small></div>}</div>
 {tab==='courts'?<CourtBrowser courts={data.courts} api={api} onEdit={open} onDelete={item=>{setError('');setDeleteTarget(item)}} busy={busy} onRefresh={load}/>:<section className="content-panel"><div className="toolbar">{tab!=='time-slots'&&<label className="search"><span>⌕</span><input aria-label="Tìm kiếm" placeholder="Tìm kiếm…" value={query} onChange={e=>setQuery(e.target.value)}/></label>}{tab==='courts'&&<select aria-label="Lọc trạng thái" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">Tất cả trạng thái</option>{['AVAILABLE','MAINTENANCE','CLOSED'].map(v=><option key={v} value={v}>{labels[v]}</option>)}</select>}{tab==='time-slots'&&<TimeSlotFilters courts={data.courts} branches={priceBranches} courtId={quoteCourt} onCourtChange={id=>{setQuoteCourt(id);setSlotPrices({});setPriceError('')}}/>}{tab==='products'&&<select aria-label="Lọc danh mục" value={filter} onChange={e=>setFilter(e.target.value)}><option value="">Tất cả danh mục</option>{data.categories.map(c=><option key={c.id} value={c.id}>{c.categoryName}</option>)}</select>}<span className="count">{rows.length} kết quả</span><button className="secondary" onClick={load} disabled={busy}>↻ Làm mới</button></div>
 {busy?<div className="empty" role="status">Đang tải dữ liệu…</div>:rows.length===0?<div className="empty"><span>◇</span><h3>{query||filter?'Không tìm thấy kết quả':'Chưa có dữ liệu'}</h3><p>{tab==='products'&&!data.categories.length?'Tạo danh mục trước khi thêm sản phẩm.':'Thêm dữ liệu mới hoặc thay đổi bộ lọc để tiếp tục.'}</p></div>:<div className="table-scroll"><table><thead><tr>{(tab==='courts'?['Số sân','Tên sân','Loại sân','Trạng thái','Giá gốc / khung giờ']:tab==='time-slots'?['Khung giờ','Hệ số giá','Phân loại','Giá tiền']:tab==='categories'?['Tên danh mục','Số sản phẩm']:['Tên sản phẩm','Danh mục','Loại','Đơn vị','Giá niêm yết','Tồn kho','Duyệt giá']).map(h=><th key={h}>{h}</th>)}<th>Thao tác</th></tr></thead><tbody>{rows.map(x=><tr key={x.id}>{tab==='courts'?<><td>#{String(x.courtNumber).padStart(2,'0')}</td><td><strong>{x.name}</strong><p className='court-location'>{x.district||'Chưa cập nhật quận / huyện'}</p><p className='court-address'>Địa chỉ: {x.address||'Chưa cập nhật địa chỉ'}</p></td><td>{labels[x.type]||'Chưa xác minh'}</td><td><span className={'badge '+x.status}>{labels[x.status]}</span></td><td>{money(x.basePrice)}</td></>:tab==='time-slots'?<><td><strong>{x.startTime.slice(0,5)} — {x.endTime.slice(0,5)}</strong></td><td><span className={'badge '+(x.priceMultiplier>1?'MAINTENANCE':'AVAILABLE')}>×{Number(x.priceMultiplier).toFixed(2)}</span></td><td>{x.priceMultiplier>1?'Giá cao điểm':'Giá thông thường'}</td><td>{!quoteCourt?'Chọn sân để xem giá':slotPrices[x.id]?money(slotPrices[x.id].price):priceError?'Không tải được giá':'Đang tải…'}</td></>:tab==='categories'?<><td><strong>{x.categoryName}</strong></td><td>{data.products.filter(p=>p.categoryId===x.id).length}</td></>:<><td><strong>{x.name}</strong></td><td>{data.categories.find(c=>c.id===x.categoryId)?.categoryName||'—'}</td><td><span className="badge neutral">{labels[x.type]}</span></td><td>{x.unit}</td><td>{money(x.price)}</td><td>{x.type==='SERVICE'?'—':x.stockQuantity}</td><td>{isImportProduct(x)?<span className={'badge '+(Number(x.price)>0?'AVAILABLE':'MAINTENANCE')}>{Number(x.price)>0?'Đã duyệt':'Chờ duyệt giá'}</span>:'—'}</td></>}<td><div className="actions">{tab==='products'&&isImportProduct(x)&&<button onClick={()=>{setPriceApprovalTarget(x);setApprovalPrice(Number(x.price)>0?String(x.price):'');setError('');}}>Duyệt giá niêm yết</button>}<button onClick={()=>open(x)}>Sửa</button><button className="danger" onClick={()=>{setError('');setDeleteTarget(x)}}>Xóa</button></div></td></tr>)}</tbody></table></div>}


 {tab==='time-slots'&&priceError&&<p role="alert" className="alert error">{priceError}</p>}
 </section>}

 </>}

 {/* POS PANEL */}
 {tab==='pos'&&<div>
  <div className="page-heading"><div><span className="eyebrow">OPERATIONS</span><h1>Bán hàng tại quầy (POS)</h1></div></div>
  <div className="pos-layout">
   <div className="pos-catalog">
    <div className="toolbar" style={{padding:'0 0 16px',borderBottom:'1px solid #edf0e8'}}>
     <label className="search" style={{width:'100%'}}><span>⌕</span><input placeholder="Tìm mặt hàng hoặc dịch vụ…" value={posSearch} onChange={e=>setPosSearch(e.target.value)}/></label>
    </div>
    <div className="pos-product-grid">
     {data.products.filter(p=>(!isImportProduct(p)||Number(p.price)>0)&&p.name.toLocaleLowerCase('vi').includes(posSearch.toLocaleLowerCase('vi'))).map(p=><div className="pos-product-card" key={p.id}>
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
     <div className="pos-summary-line"><span>Tiền sản phẩm:</span><strong>{posQuote?money(posQuote.productFee):'—'}</strong></div><div className="pos-summary-line"><span>Khuyến mãi:</span><strong>{posQuote?'−'+money(posQuote.discountAmount):'—'}</strong></div><div className="pos-summary-total"><span>{posMode==='booking'?'Tiền hàng mới sau giảm:':'Tổng thanh toán:'}</span><span>{posQuoteBusy?'Đang tính…':posQuote?money(posQuote.totalAmount):'—'}</span></div>{posQuoteError&&<div className="alert error" role="alert">{posQuoteError}</div>}


     <fieldset className="pos-customer-mode" disabled={saving}>
      <legend>Khách hàng</legend>
      <div className="pos-customer-options">
       <label><input type="radio" name="pos-mode" checked={posMode==='booking'} onChange={()=>{setPosMode('booking');setPosQuote(null);setPosBookingPreview(null);}}/> Khách thuê sân</label>
       <label><input type="radio" name="pos-mode" checked={posMode==='walk-in'} onChange={()=>{setPosMode('walk-in');setPosQuote(null);setPosBookingPreview(null);setPosBookingId('');}}/> Khách vãng lai</label>
      </div>
      {posMode==='booking'?<label className="field">Mã đơn đặt sân (UUID)<input aria-label="Mã đơn đặt sân (UUID)" placeholder="Nhập UUID trong mục Trả sân" value={posBookingId} onChange={e=>{setPosBookingId(e.target.value.trim());setPosQuote(null);setPosBookingPreview(null);}}/></label>:<p className="muted">Thu tiền hàng ngay, hóa đơn không kèm tiền sân.</p>}
     </fieldset>
     <label className="field" style={{marginTop:16}}>Mã giảm giá<input aria-label="Mã giảm giá" placeholder="Mã khuyến mãi Theo sản phẩm" maxLength={50} value={posPromotionCode} disabled={saving} onChange={e=>{setPosPromotionCode(e.target.value.toUpperCase());setPosQuote(null);}}/></label>
     {posPromotionCode.trim()&&posQuote&&!posQuoteBusy&&!posQuoteError&&<p className="promotion-applied" role="status">Mã hợp lệ. Đã tính ưu đãi tốt nhất cho sản phẩm trong tổng tiền.</p>}
     {posMode==='booking'&&posBookingPreview&&posQuote&&<div className="pos-combined-bill" aria-live="polite">
      <div className="pos-summary-line"><span>Tiền sân sau giảm:</span><strong>{money(Number(posBookingPreview.court_fee)-Number(posBookingPreview.courtDiscount||0))}</strong></div>
      <div className="pos-summary-line"><span>Tiền hàng:</span><strong>{money(Number(posBookingPreview.product_fee)+Number(posQuote.productFee))}</strong></div>
      {posPromotionCode.trim()&&<div className="pos-summary-line"><span>Tiền hàng sau giảm giá:</span><strong>{money(Math.max(0,Number(posBookingPreview.product_fee)+Number(posQuote.productFee)-Number(posBookingPreview.productDiscount||0)-Number(posQuote.discountAmount||0)))}</strong></div>}
      <div className="pos-summary-total"><span>Còn phải thu:</span><span>{money(Number(posBookingPreview.amountDue)+Number(posQuote.totalAmount))}</span></div>
     </div>}
     <button className="primary wide" style={{marginTop:'14px'}} disabled={!posCart.length||saving||posQuoteBusy||!posQuote||!!posQuoteError||(posMode==='booking'&&!posBookingPreview)} onClick={handlePosSale}>{saving?'Đang xử lý…':posMode==='booking'?'Thêm hàng & xem bill gộp':'✓ Thu tiền hàng (CASH)'}</button>
    </div>
   </div>
  </div>
  {posHistory.length>0&&<div className="content-panel" style={{marginTop:'24px'}}><div style={{padding:'16px 20px',borderBottom:'1px solid #edf0e8'}}><h3>Giao dịch POS gần đây</h3></div><div className="table-scroll"><table><thead><tr><th>Mã giao dịch</th><th>Thời gian</th><th>Số món</th><th>Khuyến mãi</th><th>Tổng tiền</th><th>Trạng thái</th></tr></thead><tbody>{posHistory.map(h=><tr key={h.id}><td><strong>{h.id}</strong></td><td>{h.time}</td><td>{h.items.length} món</td><td>{money(h.discount||0)}</td><td><strong>{money(h.total)}</strong></td><td><span className={"badge "+(h.status==='DRAFT'?'MAINTENANCE':'AVAILABLE')}>{h.status==='DRAFT'?'Chờ kết toán':'Đã thanh toán'}</span></td></tr>)}</tbody></table></div></div>}


 </div>}



 {/* ACTIVE BOOKINGS PANEL */}
{tab==='checkout' && (
  <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">OPERATIONS</span>
        <h1>Trả sân</h1>
        <p className="muted">Danh sách đơn đặt sân. Chọn Kết toán để xem bill và thu tiền.</p>
      </div>
    </div>
    <section className="content-panel">
      <div className="toolbar">
        <label className="search"><span>⌕</span>
          <input aria-label="Tìm kiếm" placeholder="Tìm kiếm…" value={query} onChange={e=>setQuery(e.target.value)} />
        </label>
        <span className="count">{filteredBookings.length} kết quả</span>
        <button className="secondary" onClick={loadOperationalData} disabled={activeBusy}>↻ Làm mới</button>
      </div>
      {activeBusy ? (
        <div className="empty" role="status">Đang tải dữ liệu…</div>
      ) : activeError ? (
        <div className="alert error" role="alert">{activeError}</div>
      ) : filteredBookings.length===0 ? (
        <div className="empty"><span>◇</span><h3>Chưa có sân đã đặt</h3></div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead><tr><th>UUID</th><th>Sân</th><th>Khách</th><th>Thời gian</th><th>Tổng tiền</th><th>Thao tác</th></tr></thead>
            <tbody>
              {filteredBookings.map((b,i) => (
                <tr key={b.bookingId ?? i}>
                  <td><code title={b.bookingId}>{(b.bookingId ?? b.shortId)?.slice(0,8) ?? '—'}</code>{b.bookingId&&<button className="secondary" style={{marginLeft:8,padding:'4px 8px'}} aria-label="Sao chép UUID đầy đủ" title="Sao chép UUID đầy đủ để mua hàng hoặc kết toán" onClick={async()=>{try{await navigator.clipboard.writeText(b.bookingId);setNotice('Đã sao chép UUID đầy đủ.');}catch{setError('Không thể sao chép UUID.');}}}>Sao chép</button>}</td>
                  <td>{b.courtDetails?.split('·')[0]?.trim() ?? '—'}</td>
                  <td>{b.customerName||'—'} ({b.customerPhone||'—'})</td>
                  <td>{b.courtDetails?.match(/\(([^)]+)\)/)?.[1] ?? '—'}</td>
                  <td>{money(b.courtFee ?? b.court_fee ?? b.totalAmount ?? b.amountDue ?? 0)}</td>
                  <td><button className="primary" disabled={saving} onClick={()=>createCheckoutSlip(b.bookingId)}>Kết toán</button></td>
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
 {tab==='checkout'&&checkoutOpen&&<div className="overlay"><section className="dialog checkout-dialog" role="dialog" aria-modal="true" aria-labelledby="checkout-dialog-title">
  <div className="dialog-head"><h2 id="checkout-dialog-title">Bill & Kết toán trả sân</h2><button aria-label="Đóng bill" disabled={saving} onClick={()=>{setCheckoutOpen(false);setError('');}}>✕</button></div>
  {error&&<div className="alert error" role="alert">{error}</div>}
  {saving&&!checkoutSlip&&<p role="status">Đang tải bill…</p>}
  {!checkoutSlip&&!saving&&<button className="secondary" onClick={()=>createCheckoutSlip()}>Tải lại bill</button>}
  <div className="checkout-layout">
   <div className="invoice-paper">

    {checkoutSlip&&<div style={{marginTop:'28px',paddingTop:'24px',borderTop:'1px solid #edf0e8'}}>
     <div style={{display:'flex',justifyContent:'space-between',alignItems:'center'}}>
      <div><span className="eyebrow">PHIẾU TẠM TÍNH</span><h2>Phiếu #{checkoutSlip.id}</h2><p className="muted">Booking: <strong>{checkoutSlip.bookingId}</strong></p></div>
      <span className={'badge '+(checkoutSlip.status==='PAID'?'AVAILABLE':'MAINTENANCE')}>{checkoutSlip.status==='PAID'?'Đã thanh toán':'Chờ thanh toán'}</span>
     </div>
     <div className="invoice-row"><span>Tiền thuê sân (court_fee):</span><strong>{money(checkoutSlip.courtFee)}</strong></div>
     <div className="invoice-row"><span>Tiền hàng gốc (product_fee):</span><strong>{money(checkoutSlip.productFee)}</strong></div>
     <div className="invoice-row"><span>Giảm tiền sân:</span><strong>{money(checkoutSlip.courtDiscount||0)}</strong></div>
     <div className="invoice-row"><span>Giảm sản phẩm:</span><strong>{money(checkoutSlip.productDiscount||0)}</strong></div>
     <div className="invoice-row"><span>Tiền sân đã xác nhận nhận:</span><strong>{money(checkoutPaidCourt)}</strong></div>
     {awaitingCourtReceipt&&<div className="alert" role="status">Khách đã khai báo chuyển khoản tiền sân {money(Number(checkoutSlip.courtFee)-Number(checkoutSlip.courtDiscount||0))}. Cần đối soát giao dịch này, không yêu cầu khách chuyển lại tiền sân.</div>}
     <div className="invoice-row"><span>{awaitingCourtReceipt?'Tiền hàng còn phải thu:':'Còn phải thu:'}</span><strong>{money(awaitingCourtReceipt?Math.max(0,Number(checkoutSlip.productFee||0)-Number(checkoutSlip.productDiscount||0)):checkoutDue)}</strong></div>
     <div className="invoice-total-block">
      <div className="total-line"><span>{awaitingCourtReceipt?'CHỜ ĐỐI SOÁT TIỀN SÂN:':'TỔNG BILL SAU GIẢM:'}</span><span>{awaitingCourtReceipt?'Chưa xác nhận':money(checkoutDue)}</span></div>
     </div>
    </div>}


   </div>

   <div className="pos-cart-panel">
    <h3>{awaitingCourtReceipt?'Đối soát tiền sân':checkoutSlip&&checkoutDue===0?'Hoàn tất trả sân':'Thu tiền còn lại'}</h3>
    {!awaitingCourtReceipt&&checkoutSlip&&checkoutDue===0&&<p className="muted">Tiền sân đã được ghi nhận. Không có khoản phát sinh cần thu thêm.</p>}
    {!awaitingCourtReceipt&&checkoutDue>0&&<><p className="muted">Chọn phương thức thu phần còn lại:</p>
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
    </div></>}
    {checkoutSlip?.courtReceiptRequired&&checkoutSlip.status!=='PAID'&&<label className="pos-customer-options"><input type="checkbox" checked={courtReceiptConfirmed} disabled={saving} onChange={e=>setCourtReceiptConfirmed(e.target.checked)}/> Tôi đã kiểm tra và nhận đủ tiền sân (tiền chuyển khoản khách đã khai báo).</label>}
    <button className="primary wide" disabled={!checkoutSlip||checkoutSlip.status==='PAID'||saving||awaitingCourtReceipt} onClick={confirmCheckout}>{saving?'Đang xử lý…':checkoutSlip&&checkoutDue===0?'✓ Hoàn tất trả sân':'✓ Xác nhận & thu tiền'}</button>
   </div>
  </div>

  {checkoutHistory.length>0&&<div className="content-panel" style={{marginTop:'24px'}}><div style={{padding:'16px 20px',borderBottom:'1px solid #edf0e8'}}><h3>Lịch sử trả sân</h3></div><div className="table-scroll"><table><thead><tr><th>Mã phiếu</th><th>Booking ID</th><th>Tiền sân</th><th>Tiền dịch vụ</th><th>Tổng thu</th><th>Phương thức</th><th>Trạng thái</th></tr></thead><tbody>{checkoutHistory.map(h=><tr key={h.id}><td><strong>{h.id}</strong></td><td>{h.bookingId}</td><td>{money(h.courtFee)}</td><td>{money(h.productFee)}</td><td><strong>{money(h.totalAmount)}</strong></td><td><span className="badge neutral">{h.paymentMethod}</span></td><td><span className="badge AVAILABLE">Đã thanh toán</span></td></tr>)}</tbody></table></div></div>}


 </section></div>}



 {tab==='statistics'&&<Statistics api={api}/>}
 {/* INVENTORY PANEL */}
 {tab==='inventory'&&<div className="inventory-panel">
  <div className="page-heading"><div><span className="eyebrow">INVENTORY</span><h1>Nhập kho</h1></div><button className="primary" onClick={()=>{setImportForm({supplierName:'',productName:'',quantity:'',totalAmount:''});setError('');setImportModalOpen(true);}}>＋ Tạo phiếu nhập</button></div>
  <PeriodFilter period={inventoryPeriod} month={inventoryMonth} range={inventoryRange} onRangeChange={r=>{setInventoryMonth('');setInventoryRange(r)}} onChange={(p,m)=>{setInventoryRange({});setInventoryPeriod(p);setInventoryMonth(m)}}/>
  <div className="stats three-stats">
   <div><span>Số phiếu nhập kho</span><strong>{visibleImportOrders.length}</strong></div>
   <div><span>Số nhà cung cấp</span><strong>{new Set(visibleImportOrders.map(o=>o.supplierId)).size}</strong></div>
   <div><span>Tổng giá trị nhập</span><strong>{money(visibleImportOrders.filter(o=>o.status!=='CANCELLED').reduce((s,o)=>s+Number(o.totalAmount||0),0))}</strong><small>Không gồm phiếu đã hủy</small></div>
  </div>
  <section className="content-panel">
   <div className="table-scroll"><table><thead><tr><th>Mã phiếu</th><th>Ngày nhập</th><th>Tổng tiền</th><th>Trạng thái</th></tr></thead><tbody>
    {!visibleImportOrders.length?<tr><td colSpan="4" style={{textAlign:'center',padding:'30px'}}>Không có phiếu nhập trong kỳ đã chọn.</td></tr>:visibleImportOrders.map(o=><tr key={o.id}>
     <td><code title={o.id}>{o.id}</code><button className="secondary" style={{marginLeft:12}} disabled={saving} onClick={()=>openImportDetail(o)}>Chi tiết</button></td>
     <td>{o.importDate}</td>
     <td><strong>{money(o.totalAmount)}</strong></td>
     <td><span className={'badge '+(o.status==='RECEIVED'?'AVAILABLE':'MAINTENANCE')}>{({DRAFT:'Chưa duyệt',CONFIRMED:'Đã xác nhận',RECEIVED:'Đã duyệt',CANCELLED:'Đã hủy'})[o.status]||o.status}</span></td>
    </tr>)}
   </tbody></table></div>
  </section>

  {importModalOpen&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-labelledby="import-title"><div className="dialog-head"><h2 id="import-title">Tạo phiếu nhập</h2><button aria-label="Đóng phiếu nhập" disabled={saving} onClick={()=>setImportModalOpen(false)}>✕</button></div><form onSubmit={handleCreateImportOrder}><fieldset className="form-grid import-fields" disabled={saving}>
   <label className="field">Nhà cung cấp<input aria-label="Nhà cung cấp" placeholder="Nhập tên nhà cung cấp" maxLength={255} value={importForm.supplierName} onChange={e=>setImportForm({...importForm,supplierName:e.target.value})} required/></label>
   <label className="field">Loại sản phẩm<input aria-label="Loại sản phẩm" placeholder="Nhập tên sản phẩm nhập kho" maxLength={255} value={importForm.productName} onChange={e=>setImportForm({...importForm,productName:e.target.value})} required/></label>
   <label className="field">Số lượng<input aria-label="Số lượng" type="number" min="1" max="2147483647" step="1" value={importForm.quantity} onChange={e=>setImportForm({...importForm,quantity:e.target.value})} required/></label>
   <label className="field">Tổng tiền<input aria-label="Tổng tiền" type="number" min="0" max="99999999.99" step="0.01" value={importForm.totalAmount} onChange={e=>setImportForm({...importForm,totalAmount:e.target.value})} required/></label>
  </fieldset>{error&&<p className="alert error" role="alert">{error}</p>}<div className="dialog-foot"><button type="button" className="secondary" disabled={saving} onClick={()=>setImportModalOpen(false)}>Hủy</button><button className="primary" disabled={saving||!importForm.supplierName.trim()||!importForm.productName.trim()||!importForm.quantity||importForm.totalAmount===''}>{saving?'Đang tạo…':'Tạo phiếu nhập'}</button></div></form></section></div>}
  {selectedImport&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-labelledby="import-detail-title"><div className="dialog-head"><h2 id="import-detail-title">Chi tiết phiếu nhập</h2><button aria-label="Đóng chi tiết phiếu nhập" disabled={saving} onClick={()=>setSelectedImport(null)}>✕</button></div><p className="invoice-reference">Mã phiếu: {selectedImport.id}</p><p>Nhà cung cấp: {suppliers.find(s=>s.id===selectedImport.supplierId)?.name||'—'}</p><p>Ngày nhập: {selectedImport.importDate}</p><p>Trạng thái: {({DRAFT:'Chờ duyệt',CONFIRMED:'Đã xác nhận',RECEIVED:'Đã nhập kho',CANCELLED:'Đã hủy'})[selectedImport.status]||selectedImport.status}</p><div className="table-scroll"><table><thead><tr><th>Loại sản phẩm</th><th>Số lượng</th><th>Tổng tiền</th></tr></thead><tbody>{[...new Set((selectedImport.details||[]).map(d=>d.productId))].map(id=>{const lines=selectedImport.details.filter(d=>d.productId===id);return <tr key={id}><td>{data.products.find(p=>p.id===id)?.name||id}</td><td>{lines.reduce((s,d)=>s+d.quantity,0)}</td><td>{money(lines.reduce((s,d)=>s+d.quantity*Number(d.importPrice),0))}</td></tr>})}</tbody></table></div><div className="invoice-row"><span>Tổng tiền:</span><strong>{money(selectedImport.totalAmount)}</strong></div>{error&&<p className="alert error" role="alert">{error}</p>}<div className="dialog-foot"><button className="secondary" disabled={saving} onClick={()=>setSelectedImport(null)}>Đóng</button>{['DRAFT','CONFIRMED'].includes(selectedImport.status)&&<button className="primary" disabled={saving} onClick={()=>handleApproveImportOrder(selectedImport.id)}>{saving?'Đang duyệt…':'Duyệt nhập kho'}</button>}</div></section></div>}


 </div>}



 {/* ANALYTICS PANEL */}
 {tab==='analytics'&&<div className="analytics-panel">
  <div className="page-heading">
   <div><span className="eyebrow">ANALYTICS</span><h1>Doanh thu</h1></div>
   <PeriodFilter period={analyticsPeriod} month={analyticsMonth} range={analyticsRange} onRangeChange={r=>{setRevenueData(null);setAnalyticsMonth('');setAnalyticsRange(r)}} onChange={(p,m)=>{setRevenueData(null);setAnalyticsRange({});setAnalyticsPeriod(p);setAnalyticsMonth(m)}}/>
  </div>
  <div className="stats">
   <div><span>Doanh thu sau giảm giá</span><strong>{money(revenueData?.total_amount||revenueData?.totalAmount||0)}</strong><small className="green">{analyticsRange.from?'Khoảng ngày đã chọn':analyticsMonth?'Tháng '+analyticsMonth:({DAY:'Hôm nay',WEEK:'Tuần này',MONTH:'Tháng này'})[analyticsPeriod]}</small></div>
   <div><span>Tiền sân</span><strong>{money(revenueData?.court_fee||revenueData?.courtFee||0)}</strong></div>
   <div><span>Hàng hóa & dịch vụ</span><strong>{money(revenueData?.product_fee||revenueData?.productFee||0)}</strong></div>
   <div><span>Thời gian ghi nhận</span><strong>{revenueData?.from||'—'}</strong><small>Đến {revenueData?.to||'—'}</small></div>
  </div>
  <RevenueInvoices key={analyticsParams} api={api} params={analyticsParams}/>
 </div>}



 </div><footer>BCM · Master Data & Operations Desk <span>Sân cầu · Khung giờ · Danh mục · Hàng hóa · POS · Trả sân · Kho</span></footer></main>

 {modal&&<div className="overlay"><section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-head"><h2 id="dialog-title">{modal.id?'Chỉnh sửa':'Thêm mới'} · {title}</h2><button aria-label="Đóng" disabled={saving} onClick={()=>{setModal(null);setError('')}}>✕</button></div><form onSubmit={save}><div className="form-grid">{tab==='courts'?<>{field('courtNumber','Số sân','number',{min:1,step:1})}{field('name','Tên sân','text',{maxLength:255})}{field('district','Quận / Huyện','text',{maxLength:150,required:false})}{field('address','Địa chỉ sân','text',{maxLength:500,required:false})}<p className="form-help">Địa chỉ tối đa 500 ký tự, thống nhất với quận / huyện. Để trống địa chỉ khi sửa sẽ giữ dữ liệu đang lưu.</p>{select('type','Loại sân',['STANDARD_MAT'].map(v=>[v,labels[v]]))}{select('status','Trạng thái',['AVAILABLE','MAINTENANCE','CLOSED'].map(v=>[v,labels[v]]))}{field('basePrice','Giá gốc (VNĐ / khung giờ)','number',{min:0,max:99999999.99,step:0.01})}</>:tab==='time-slots'?<>{field('startTime','Giờ bắt đầu','time')}{field('endTime','Giờ kết thúc','time')}{field('priceMultiplier','Hệ số giá','number',{min:0.01,max:9.99,step:0.01})}<p className="form-help">Mặc định 17:00–21:00 ×1.5, giờ khác ×1.0. Có thể điều chỉnh hệ số riêng. Khung giờ không được chồng lấn.</p></>:tab==='categories'?field('categoryName','Tên danh mục','text',{maxLength:255}):<>{field('name','Tên hàng hóa / dịch vụ','text',{maxLength:255})}{select('categoryId','Danh mục',data.categories.map(c=>[c.id,c.categoryName]))}{select('type','Loại',['GOODS','SERVICE'].map(v=>[v,labels[v]]))}{field('unit','Đơn vị tính','text',{maxLength:50})}{field('price','Giá niêm yết (VNĐ)','number',{min:0,max:99999999.99,step:0.01,disabled:isImportProduct(form)})}{field('stockQuantity',modal.id?'Tồn kho hiện tại':'Tồn ban đầu','number',{min:0,max:2147483647,step:1,disabled:!!modal.id||form.type==='SERVICE'})}<p className="form-help">Tồn ban đầu chỉ nhập khi tạo sản phẩm. Dịch vụ không theo dõi tồn kho. Điều chỉnh tồn sau này thuộc phân hệ kho.</p></>}</div>{error&&<div className="alert error" role="alert">{error}</div>}

<div className="dialog-foot"><button type="button" className="secondary" disabled={saving} onClick={()=>{setModal(null);setError('')}}>Hủy</button><button className="primary" disabled={saving}>{saving?'Đang lưu…':'Lưu thay đổi'}</button></div></form></section></div>}


 {deleteTarget&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="delete-title"><h2 id="delete-title">Xóa dữ liệu?</h2><p>Bạn muốn xóa <strong>{deleteTarget.name||deleteTarget.categoryName||deleteTarget.startTime+' – '+deleteTarget.endTime}</strong> khỏi danh sách?</p>{error&&<div className="alert error" role="alert">{error}</div>}

<div className="dialog-foot"><button className="secondary" disabled={saving} onClick={()=>{setDeleteTarget(null);setError('')}}>Hủy</button><button className="primary delete" disabled={saving} onClick={remove}>{saving?'Đang xóa…':'Xác nhận xóa'}</button></div></section></div>}


 {priceApprovalTarget&&<div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="approve-price-title"><div className="dialog-head"><h2 id="approve-price-title">Duyệt giá niêm yết</h2><button aria-label="Đóng duyệt giá" disabled={saving} onClick={()=>setPriceApprovalTarget(null)}>✕</button></div><p>{priceApprovalTarget.name}</p><form onSubmit={approveProductPrice}><label className="field">Giá niêm yết (VNĐ)<input aria-label="Giá niêm yết cần duyệt" type="number" min="0.01" max="99999999.99" step="0.01" value={approvalPrice} disabled={saving} required onChange={e=>setApprovalPrice(e.target.value)}/></label>{error&&<p role="alert" className="alert error">{error}</p>}<div className="dialog-foot"><button type="button" className="secondary" disabled={saving} onClick={()=>setPriceApprovalTarget(null)}>Hủy</button><button className="primary" disabled={saving||!approvalPrice||Number(approvalPrice)<=0}>{saving?'Đang duyệt…':'Duyệt & cho phép bán'}</button></div></form></section></div>}
 </div>;
}
