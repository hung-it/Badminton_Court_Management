import {useState} from 'react';
import {Navigate} from 'react-router-dom';
import {useAuth} from '../lib/authContext';
import {apiPost,toErrorMessage} from '../lib/apiClient';
import './profile.css';

export default function ProfilePage(){
 const {user,isAuthenticated}=useAuth();
 const [form,setForm]=useState({currentPassword:'',newPassword:'',confirmation:''}),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 if(!isAuthenticated)return <Navigate to="/login?redirect=%2Fprofile" replace/>;
 async function submit(e){
  e.preventDefault();setError('');setNotice('');
  if(form.newPassword!==form.confirmation){setError('Mật khẩu xác nhận không khớp.');return;}
  if(new TextEncoder().encode(form.newPassword).length>72){setError('Mật khẩu mới không được vượt quá 72 byte UTF-8.');return;}
  setBusy(true);
  try{await apiPost('/users/me/password',{currentPassword:form.currentPassword,newPassword:form.newPassword});setForm({currentPassword:'',newPassword:'',confirmation:''});setNotice('Đổi mật khẩu thành công.');}
  catch(e){setError(toErrorMessage(e));}finally{setBusy(false)}
 }
 return <section className="profile-page"><h1>Hồ sơ cá nhân</h1><p>{user?.fullName} · {user?.email}</p><h2>Đổi mật khẩu</h2><form onSubmit={submit}><fieldset disabled={busy}>{[['currentPassword','Mật khẩu hiện tại'],['newPassword','Mật khẩu mới'],['confirmation','Xác nhận mật khẩu mới']].map(([key,label])=><label key={key}>{label}<input type="password" autoComplete={key==='currentPassword'?'current-password':'new-password'} value={form[key]} minLength={key==='currentPassword'?1:8} maxLength={key==='currentPassword'?100:72} required onChange={e=>setForm({...form,[key]:e.target.value})}/></label>)}</fieldset>{error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}<button disabled={busy}>{busy?'Đang lưu…':'Lưu mật khẩu'}</button></form></section>;
}
