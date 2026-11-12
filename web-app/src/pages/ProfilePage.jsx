import {useEffect,useRef,useState} from 'react';
import {Link,Navigate} from 'react-router-dom';
import {useAuth} from '../lib/authContext';
import {apiGet,apiPost,apiPut,toErrorMessage} from '../lib/apiClient';
import './profile.css';

export default function ProfilePage(){
 const {user,isAuthenticated,updateUser}=useAuth();
 const [profile,setProfile]=useState(null),[draft,setDraft]=useState({}),[editing,setEditing]=useState(false);
 const [loading,setLoading]=useState(true),[loadError,setLoadError]=useState(''),[retry,setRetry]=useState(0);
 const [saving,setSaving]=useState(false),[profileError,setProfileError]=useState(''),[profileNotice,setProfileNotice]=useState('');
 const identity=useRef(null);identity.current=isAuthenticated?user?.id:null;
 const mounted=useRef(false);
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
 useEffect(()=>{
  if(!isAuthenticated||!user?.id)return;
  let active=true;setLoading(true);setLoadError('');setProfile(null);setEditing(false);
  apiGet('/users/me').then(value=>{if(active&&identity.current===value?.id){setProfile(value);updateUser(value)}})
   .catch(e=>{if(active)setLoadError(toErrorMessage(e))}).finally(()=>{if(active)setLoading(false)});
  return()=>{active=false};
 },[isAuthenticated,user?.id,retry,updateUser]);
 const [form,setForm]=useState({currentPassword:'',newPassword:'',confirmation:''}),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 if(!isAuthenticated)return <Navigate to="/login?redirect=%2Fprofile" replace/>;
 async function saveProfile(e){
  e.preventDefault();if(saving)return;
  const owner=identity.current;setSaving(true);setProfileError('');setProfileNotice('');
  try{
   const value=await apiPut('/users/me',{fullName:draft.fullName.trim(),phone:draft.phone.trim(),address:draft.address?.trim()||null});
   if(mounted.current&&identity.current===owner&&value?.id===owner&&updateUser(value)){
    setProfile(value);setEditing(false);setProfileNotice('Cập nhật thông tin thành công.');
   }
  }catch(e){if(mounted.current&&identity.current===owner)setProfileError(toErrorMessage(e))}
  finally{if(mounted.current&&identity.current===owner)setSaving(false)}
 }
 async function submit(e){
  e.preventDefault();setError('');setNotice('');
  if(form.newPassword!==form.confirmation){setError('Mật khẩu xác nhận không khớp.');return;}
  if(new TextEncoder().encode(form.newPassword).length>72){setError('Mật khẩu mới không được vượt quá 72 byte UTF-8.');return;}
  setBusy(true);
  try{await apiPost('/users/me/password',{currentPassword:form.currentPassword,newPassword:form.newPassword});setForm({currentPassword:'',newPassword:'',confirmation:''});setNotice('Đổi mật khẩu thành công.');}
  catch(e){setError(toErrorMessage(e));}finally{setBusy(false)}
 }
 return <section className="profile-page"><header className="profile-heading"><span className="profile-eyebrow">TÀI KHOẢN CỦA BẠN</span><h1>Thông tin cá nhân</h1><p>Quản lý thông tin liên hệ và bảo mật tài khoản.</p><Link to="/my-bookings">Xem lịch đặt của tôi →</Link></header>
 <div className="profile-columns"><section className="profile-card" aria-labelledby="profile-information"><h2 id="profile-information">Hồ sơ của tôi</h2>
 {loading?<p role="status">Đang tải thông tin…</p>:loadError?<div role="alert"><p>{loadError}</p><button onClick={()=>setRetry(v=>v+1)}>Thử lại</button></div>:profile&&<>
 {editing?<form onSubmit={saveProfile}><fieldset disabled={saving}>
 <label>Email đăng nhập<input type="email" value={profile.email} readOnly/><small>Email đăng nhập hiện chưa hỗ trợ thay đổi.</small></label>
 {[['fullName','Họ và tên','text',255,'name'],['phone','Số điện thoại','tel',20,'tel'],['address','Địa chỉ','text',2000,'street-address']].map(([key,label,type,maxLength,autoComplete])=><label key={key}>{label}<input type={type} maxLength={maxLength} autoComplete={autoComplete} required={key!=='address'} pattern={key==='phone'?'[+0-9][0-9 .-]{7,19}':undefined} value={draft[key]||''} onChange={e=>setDraft({...draft,[key]:e.target.value})}/></label>)}
 </fieldset>{profileError&&<p role="alert">{profileError}</p>}<div className="profile-actions"><button disabled={saving}>{saving?'Đang lưu…':'Lưu thay đổi'}</button><button type="button" className="profile-secondary" disabled={saving} onClick={()=>{setEditing(false);setProfileError('')}}>Hủy</button></div></form>
 :<><dl className="profile-details">{[['Họ và tên',profile.fullName],['Email đăng nhập',profile.email],['Số điện thoại',profile.phone],['Địa chỉ',profile.address]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value||'Chưa cập nhật'}</dd></div>)}</dl>{profile.canEditProfile&&<button onClick={()=>{setDraft({fullName:profile.fullName,phone:profile.phone,address:profile.address||''});setProfileNotice('');setProfileError('');setEditing(true)}}>Chỉnh sửa</button>}</>}
 {profileNotice&&<p role="status">{profileNotice}</p>}</>}
 </section><section className="profile-card" aria-labelledby="profile-security"><h2 id="profile-security">Đổi mật khẩu</h2><p className="profile-help">Mật khẩu mới có ít nhất 8 ký tự, không quá 72 byte UTF-8.</p><form onSubmit={submit}><fieldset disabled={busy}>{[['currentPassword','Mật khẩu hiện tại'],['newPassword','Mật khẩu mới'],['confirmation','Xác nhận mật khẩu mới']].map(([key,label])=><label key={key}>{label}<input type="password" autoComplete={key==='currentPassword'?'current-password':'new-password'} value={form[key]} minLength={key==='currentPassword'?1:8} maxLength={key==='currentPassword'?100:72} required onChange={e=>setForm({...form,[key]:e.target.value})}/></label>)}</fieldset>{error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}<button disabled={busy}>{busy?'Đang lưu…':'Lưu mật khẩu'}</button></form></section></div></section>;
}
