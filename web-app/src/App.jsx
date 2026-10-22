import { useEffect } from 'react';
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router-dom';
import Header from './components/Header';
import Footer from './components/Footer';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import PlaceholderPage from './pages/PlaceholderPage';
import { AuthProvider } from './lib/authContext';

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
    const onExpired = () => navigate('/login', { replace: true });
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
        <Layout>
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            {/* Các màn hình còn lại sẽ triển khai ở phase sau — giữ route để link không gãy. */}
            <Route path="/booking" element={<PlaceholderPage title="Đặt sân" />} />
            <Route path="/courts" element={<PlaceholderPage title="Sân cầu lông" />} />
            <Route path="/guide" element={<PlaceholderPage title="Hướng dẫn" />} />
            <Route path="/contact" element={<PlaceholderPage title="Liên hệ" />} />
            <Route path="/my-bookings" element={<PlaceholderPage title="Lịch đặt của tôi" />} />
            <Route path="/profile" element={<PlaceholderPage title="Hồ sơ cá nhân" />} />
            <Route path="*" element={<PlaceholderPage title="Không tìm thấy trang" />} />
          </Routes>
        </Layout>
      </AuthProvider>
    </BrowserRouter>
  );
}
