import React from 'react';
import {createRoot} from 'react-dom/client';
import './customer-design.css';
import CustomerCourts from './CustomerCourts.jsx';
import CustomerGuide from './CustomerGuide.jsx';
import CustomerContact from './CustomerContact.jsx';
import CustomerCheckout from './CustomerCheckout.jsx';

if (window.location.pathname === '/') {
  window.history.replaceState({}, '', '/courts');
}

const pages = {
  '/guide': {component: CustomerGuide, title: 'Hướng dẫn · Sân cầu lông'},
  '/contact': {component: CustomerContact, title: 'Liên hệ · Sân cầu lông'},
  '/checkout': {component: CustomerCheckout, title: 'Xác nhận đặt sân · Sân cầu lông'},
};
const page = pages[window.location.pathname] || {component: CustomerCourts, title: 'Sân cầu lông · Đặt sân'};
const Page = page.component;
document.title = page.title;

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <page.component />
  </React.StrictMode>,
);
