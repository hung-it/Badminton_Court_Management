import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { existsSync } from 'node:fs';
export default defineConfig(async ({command})=>{
 const localConfig=new URL('./vite.local.mjs',import.meta.url);
 const localPlugins=command==='serve'&&existsSync(localConfig)?[(await import(localConfig.href)).default()]:[];
 return { plugins:[...localPlugins,react()], server:{port:5173,strictPort:true,proxy:{'/api':{target:process.env.BCM_API_PROXY_TARGET||'http://127.0.0.1:8080',changeOrigin:true}}} };
});
