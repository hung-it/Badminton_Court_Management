import { useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import Header from './components/Header';
import Footer from './components/Footer';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import PlaceholderPage from './pages/PlaceholderPage';
import { AuthProvider } from './lib/authContext';
import CustomerCourts from './CustomerCourts';
import CustomerCheckout from './CustomerCheckout';
import CustomerGuide from './CustomerGuide';
import CustomerContact from './CustomerContact';
import PaymentResult from './PaymentResult';

function BookingRedirect() {
  const location = useLocation();
  return <Navigate to={`/courts${location.search}${location.hash}`} replace />;
}

function Layout({ children }) {
  return (
    <div className="app-shell">
      <Header />
      <main>{children}</main>
      <Footer />
    </div>
  );
}

/** Token hết hạn và không refresh được → đưa người dùng về trang đăng nhập. */
function AuthExpiryGuard() {
  const navigate = useNavigate();
  useEffect(() => {
    const onExpired = () => {
      if (['/login', '/register'].includes(window.location.pathname)) return;
      navigate(`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`, { replace: true });
    };
    window.addEventListener('bcm:auth-expired', onExpired);
    return () => window.removeEventListener('bcm:auth-expired', onExpired);
  }, [navigate]);
  return null;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AuthExpiryGuard />
          <Routes>
            <Route path="/" element={<Layout><HomePage /></Layout>} />
            <Route path="/login" element={<Layout><LoginPage /></Layout>} />
            <Route path="/register" element={<Layout><RegisterPage /></Layout>} />
            <Route path="/booking" element={<BookingRedirect />} />
            <Route path="/courts" element={<CustomerCourts />} />
            <Route path="/checkout" element={<CustomerCheckout />} />
            <Route path="/payment-result" element={<PaymentResult />} />
            <Route path="/payment/result" element={<PaymentResult />} />
            <Route path="/guide" element={<CustomerGuide />} />
            <Route path="/contact" element={<CustomerContact />} />
            <Route path="/my-bookings" element={<Navigate to="/courts#upcoming-booking" replace />} />
            <Route path="/profile" element={<Layout><PlaceholderPage title="Hồ sơ cá nhân" /></Layout>} />
            <Route path="*" element={<Layout><PlaceholderPage title="Không tìm thấy trang" /></Layout>} />
          </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
