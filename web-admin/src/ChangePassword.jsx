import React,{useState} from 'react';
export default function ChangePassword({api,onClose}){
 const [form,setForm]=useState({currentPassword:'',newPassword:'',confirmPassword:''}),[busy,setBusy]=useState(false),[error,setError]=useState(''),[done,setDone]=useState(false);
 async function submit(e){
  e.preventDefault();setError('');
  if(form.newPassword!==form.confirmPassword){setError('Mật khẩu xác nhận không khớp.');return;}
  setBusy(true);
  try{await api('/users/me/password','POST',{currentPassword:form.currentPassword,newPassword:form.newPassword});setForm({currentPassword:'',newPassword:'',confirmPassword:''});setDone(true);}
  catch(e){setError(e.message)}finally{setBusy(false)}
 }
 return <div className="overlay"><section className="dialog small" role="dialog" aria-modal="true" aria-labelledby="password-title"><div className="dialog-head"><h2 id="password-title">Đổi mật khẩu</h2><button aria-label="Đóng đổi mật khẩu" disabled={busy} onClick={onClose}>✕</button></div>
  {done?<p role="status">Đổi mật khẩu thành công.</p>:<form onSubmit={submit}><fieldset disabled={busy} className="form-grid">{[['currentPassword','Mật khẩu hiện tại'],['newPassword','Mật khẩu mới'],['confirmPassword','Xác nhận mật khẩu mới']].map(([key,label])=><label className="field" key={key}>{label}<input type="password" autoComplete={key==='currentPassword'?'current-password':'new-password'} value={form[key]} minLength={key==='currentPassword'?1:8} maxLength={72} required onChange={e=>setForm({...form,[key]:e.target.value})}/></label>)}</fieldset>{error&&<p className="alert error" role="alert">{error}</p>}<div className="dialog-foot"><button className="primary" disabled={busy}>{busy?'Đang lưu…':'Lưu mật khẩu'}</button></div></form>}
 </section></div>;
}
