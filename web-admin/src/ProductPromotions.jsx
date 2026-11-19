import React from 'react';
export const ruleLabels={PRODUCT:'Sản phẩm',INVOICE_TOTAL:'Hóa đơn',VOUCHER:'Voucher'};
export const newRule=type=>({_key:crypto.randomUUID(),id:null,ruleType:type,productId:'',minQuantity:1,discountType:'PERCENT',discountValue:10,minInvoiceAmount:0,maxDiscountAmount:'',maxTotalUsage:'',maxUsagePerCustomer:'',voucherCode:'',active:true,removed:false});
// The program owns the form and saves its rules in one transaction.
export default function ProductPromotions({rule:r,products,change}){
 const number=(key,label,min=0,extra={})=><label className="field">{label}<input aria-label={label} type="number" min={min} step="0.01" value={r[key]} onChange={e=>change(key,e.target.value)} {...extra}/></label>;
 return <div className="form-grid promotion-rule-fields">
 {r.ruleType==='PRODUCT'&&<><label className="field">Sản phẩm<select aria-label="Sản phẩm" required value={r.productId} onChange={e=>change('productId',e.target.value)}><option value="">Chọn sản phẩm</option>{r.productId&&!products.some(p=>p.id===r.productId)&&<option value={r.productId}>{r.productName||'Sản phẩm đã ngừng bán'}</option>}{products.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label>{number('minQuantity','Số lượng tối thiểu',1,{required:true,step:1,max:2147483647})}</>}
 {r.ruleType==='VOUCHER'&&<label className="field">Mã voucher<input aria-label="Mã voucher" required disabled={!!r.id} pattern="[A-Za-z0-9_-]{2,50}" maxLength={50} value={r.voucherCode} onChange={e=>change('voucherCode',e.target.value.toUpperCase())}/></label>}
 {r.ruleType!=='PRODUCT'&&number('minInvoiceAmount','Giá trị tối thiểu',0,{required:true,max:99999999.99})}
 <label className="field">Kiểu giảm<select aria-label="Kiểu giảm" value={r.discountType} onChange={e=>change('discountType',e.target.value)}><option value="PERCENT">Phần trăm</option>{r.ruleType!=='PRODUCT'&&<option value="AMOUNT">Số tiền</option>}</select></label>
 {number('discountValue',r.discountType==='PERCENT'?'Mức giảm (%)':'Mức giảm (đ)',0.01,{required:true,max:r.discountType==='PERCENT'?(r.ruleType==='PRODUCT'?100:99.99):99999999.99})}
 {r.ruleType!=='PRODUCT'&&number('maxDiscountAmount','Giảm tối đa (đ)',0.01,{placeholder:'Không giới hạn',max:99999999.99})}
 {r.ruleType==='VOUCHER'&&<>{number('maxTotalUsage','Tổng lượt sử dụng',1,{step:1,max:2147483647,placeholder:'Không giới hạn'})}{number('maxUsagePerCustomer','Lượt mỗi khách',1,{step:1,max:2147483647,placeholder:'Không giới hạn'})}<label className="field">Điều kiện khách hàng<input aria-label="Điều kiện khách hàng" readOnly value={r.voucherCode==='NEWMEMBER'?'Khách hàng mới':r.voucherCode==='KMKH10'?'Khách hàng thân thiết':'Tất cả khách hàng'}/></label></>}
 <label className="field">Hiệu lực quy tắc<select aria-label="Hiệu lực quy tắc" value={String(r.active)} onChange={e=>change('active',e.target.value==='true')}><option value="true">Bật</option><option value="false">Tắt</option></select></label>
 </div>
}
