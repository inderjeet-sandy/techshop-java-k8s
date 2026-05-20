#!/usr/bin/env node
// TechShop Java — Load Generator
// Usage: BACKEND_URL=http://YOUR_LB RATE=5 node load-generator.js

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const RATE        = parseInt(process.env.RATE || '3');
const http        = require('http');
const https       = require('https');

const PRODUCTS = [1,2,3,4,5,6,7,8,9,10,11,12];
const EMAILS   = ['alice@demo.com','bob@demo.com','carol@demo.com','dave@demo.com'];
const SEARCHES = ['laptop','keyboard','monitor','ssd','usb','wifi','stand'];

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

function req(path, method='GET', body=null) {
    return new Promise((resolve) => {
        const url  = new URL(BACKEND_URL + path);
        const mod  = url.protocol === 'https:' ? https : http;
        const opts = {
            hostname: url.hostname, port: url.port || (url.protocol === 'https:' ? 443 : 80),
            path: url.pathname + url.search, method,
            headers: { 'Content-Type': 'application/json', 'X-Session-Id': 'loadgen-' + process.pid }
        };
        const r = mod.request(opts, res => {
            let d = '';
            res.on('data', c => d += c);
            res.on('end', () => {
                try { resolve({ status: res.statusCode, body: JSON.parse(d) }); }
                catch { resolve({ status: res.statusCode, body: d }); }
            });
        });
        r.on('error', () => resolve({ status: 0 }));
        if (body) r.write(JSON.stringify(body));
        r.end();
    });
}

async function scenario() {
    const roll = Math.random();
    try {
        if (roll < 0.30) {
            // Browse products
            const res = await req('/api/products');
            if (res.status === 200) console.log(`  📦 Browse all → ${res.body.length} products`);

        } else if (roll < 0.50) {
            // Search
            const q = pick(SEARCHES);
            const res = await req(`/api/products?search=${q}`);
            console.log(`  🔍 Search "${q}" → ${res.body?.length || 0} results`);

        } else if (roll < 0.65) {
            // Category browse
            const cats = ['Laptops','Peripherals','Networking','Storage','Accessories'];
            const cat  = pick(cats);
            const res  = await req(`/api/products?category=${encodeURIComponent(cat)}`);
            console.log(`  📂 Category "${cat}" → ${res.body?.length || 0} results`);

        } else if (roll < 0.80) {
            // Add to cart
            const pid = pick(PRODUCTS);
            const res = await req('/api/cart/add', 'POST', { productId: pid, quantity: 1 });
            if (res.status === 200)
                console.log(`  🛒 Added product ${pid} → $${res.body?.itemTotal?.toFixed(2)}`);

        } else if (roll < 0.92) {
            // Checkout
            const items = [{ productId: pick(PRODUCTS), quantity: 1 }];
            if (Math.random() > 0.5) items.push({ productId: pick(PRODUCTS), quantity: 1 });
            const res = await req('/api/orders/checkout', 'POST',
                { customerEmail: pick(EMAILS), items });
            if (res.status === 200)
                console.log(`  🎉 Order ${res.body?.orderNumber} → $${Number(res.body?.total).toFixed(2)}`);
            else
                console.log(`  ❌ Checkout failed: ${res.status}`);

        } else {
            // 404 errors (for NR Error Analytics)
            await req('/api/products/99999');
            console.log(`  💥 404 — product not found (error demo)`);
        }
    } catch (e) { /* ignore */ }
}

async function main() {
    // Health check
    const health = await req('/api/health');
    if (health.status !== 200) {
        console.error(`Backend not reachable at ${BACKEND_URL}`);
        process.exit(1);
    }
    console.log(`✅ Backend ready: ${health.body?.service}`);
    console.log(`🚀 Load generator started — ${RATE} req/sec against ${BACKEND_URL}\n`);

    setInterval(async () => {
        for (let i = 0; i < RATE; i++) {
            setTimeout(scenario, Math.random() * 1000);
        }
    }, 1000);
}

main();
