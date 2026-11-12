export function canResumeBooking(booking,serverNowMs){
  if(booking.status!=='PENDING'||!Number.isFinite(serverNowMs)||!booking.expiresAt)return false;
  // LocalDateTime from the API is the business clock in Vietnam, never browser local time.
  const deadline=Date.parse(booking.expiresAt+'+07:00');
  return Number.isFinite(deadline)&&deadline>serverNowMs;
}

export function holdDurationLabel(seconds){
  if(typeof seconds!=='number'||!Number.isFinite(seconds)||seconds<=0)return null;
  const minutes=Math.floor(seconds/60),remainder=seconds%60;
  return [minutes?`${minutes} phút`:'',remainder?`${remainder} giây`:''].filter(Boolean).join(' ');
}
