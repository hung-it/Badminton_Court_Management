import {useEffect, useState} from 'react';
import client from './apiClient';
import {anchorBookingClock, bookingToday} from './bookingTime';

export function useBookingClock() {
  const [nowMs, setNowMs] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    let readClock = null;
    let syncing = false;
    const tick = () => { if (active && readClock) setNowMs(readClock(performance.now())); };
    const sync = async () => {
      if (syncing) return;
      syncing = true;
      try {
        const response = await client.get('/public/booking-clock');
        if (!response.data?.success) throw new Error('Không đồng bộ được thời gian máy chủ.');
        const anchored = anchorBookingClock(response.data.data, performance.now());
        if (!active) return;
        readClock = anchored;
        setError(''); tick();
      } catch { if (active) setError('Không đồng bộ được giờ đặt sân. Vui lòng thử lại hoặc tải lại trang.'); }
      finally { syncing = false; }
    };
    const visible = () => { if (document.visibilityState === 'visible') sync(); };
    sync();
    const timer = window.setInterval(tick, 250);
    const poll = window.setInterval(sync, 30000);
    window.addEventListener('focus', sync);
    document.addEventListener('visibilitychange', visible);
    return () => {
      active = false; window.clearInterval(timer); window.clearInterval(poll);
      window.removeEventListener('focus', sync); document.removeEventListener('visibilitychange', visible);
    };
  }, []);
  return {nowMs, today: bookingToday(nowMs), ready: nowMs !== null, error};
}
