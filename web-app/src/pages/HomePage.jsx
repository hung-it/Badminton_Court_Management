import { useCallback, useEffect, useState } from 'react';
import { AlertCircle } from 'lucide-react';
import HomeHero from '../components/HomeHero';
import HomeSearch from '../components/HomeSearch';
import HomeFeaturedCourts from '../components/HomeFeaturedCourts';
import HomeSteps from '../components/HomeSteps';
import HomeTestimonials from '../components/HomeTestimonials';
import HomeFaq from '../components/HomeFaq';
import { fetchCourts, fetchTimeSlots } from '../lib/publicApi';
import { toErrorMessage } from '../lib/apiClient';

export default function HomePage() {
  const [courts, setCourts] = useState([]);
  const [timeSlots, setTimeSlots] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // Hai endpoint public độc lập → gọi song song.
      const [courtList, slotList] = await Promise.all([fetchCourts(), fetchTimeSlots()]);
      setCourts(courtList);
      setTimeSlots(slotList);
    } catch (err) {
      setError(toErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <>
      {error && courts.length === 0 && (
        <div className="container" style={{ paddingTop: 24 }}>
          <div className="alert alert--error">
            <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
            <span>{error}</span>
          </div>
        </div>
      )}

      <HomeHero courtCount={courts.length} loading={loading} />

      <HomeSearch courts={courts} timeSlots={timeSlots} loading={loading} />

      <HomeFeaturedCourts
        courts={courts}
        loading={loading}
        error={error && courts.length === 0 ? error : ''}
        onRetry={load}
      />

      <HomeSteps />
      <HomeTestimonials />
      <HomeFaq />
    </>
  );
}
