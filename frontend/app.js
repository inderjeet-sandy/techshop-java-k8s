// TechShop Java — Frontend SPA
// New Relic Browser API calls are throughout to demonstrate:
//   newrelic.addPageAction()      — custom user interaction events
//   newrelic.setCustomAttribute() — dimensions on all BrowserInteraction events
//   newrelic.noticeError()        — manual JS error reporting
//   newrelic.setCurrentRouteName()— SPA route tracking

const API = '';   // empty = same origin (nginx proxies /api/* to backend)
const cart = [];
const chaosFlags = { 'slow-checkout': false, 'n-plus-one': false, 'payment-errors': false, 'cardinality-bomb': false };

// ── Bootstrap ──────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
    if (typeof newrelic !== 'undefined') {
        newrelic.setCustomAttribute('appVersion', '1.0.0');
        newrelic.setCustomAttribute('framework',  'vanilla-js');
    }
    loadCategories();
    loadProducts();
});

// ── Products ───────────────────────────────────────────────────────────
async function loadProducts(category = null) {
    if (typeof newrelic !== 'undefined') {
        newrelic.setCurrentRouteName(category ? `/category/${category}` : '/products');
        newrelic.interaction().setAttribute('category', category || 'all').save();
    }

    const url = category ? `${API}/api/products?category=${encodeURIComponent(category)}` : `${API}/api/products`;
    const container = document.getElementById('products');
    container.innerHTML = '<div class="loading">Loading...</div>';

    try {
        const res  = await fetch(url);
        const data = await res.json();
        renderProducts(data);

        if (typeof newrelic !== 'undefined') {
            newrelic.addPageAction('ProductsLoaded', { count: data.length, category: category || 'all' });
        }
    } catch (err) {
        container.innerHTML = '<div class="status error">Failed to load products</div>';
        if (typeof newrelic !== 'undefined') newrelic.noticeError(err, { context: 'loadProducts' });
    }
}

function renderProducts(products) {
    const container = document.getElementById('products');
    if (!products.length) { container.innerHTML = '<div class="status info">No products found.</div>'; return; }
    container.innerHTML = products.map(p => `
        <div class="product-card" onclick="viewProduct(${p.id})">
            <div class="category">${p.category}</div>
            <h3>${p.name}</h3>
            <p style="font-size:0.85rem;color:#718096;margin:0.5rem 0">${p.description || ''}</p>
            <div class="price">$${Number(p.price).toFixed(2)}</div>
            <div class="stock">${p.stockQuantity > 0 ? `${p.stockQuantity} in stock` : 'Out of stock'}</div>
            <button class="add-btn" onclick="event.stopPropagation(); addToCart(${p.id},'${p.name}',${p.price},'${p.category}')">Add to Cart</button>
        </div>
    `).join('');
}

function viewProduct(id) {
    if (typeof newrelic !== 'undefined') {
        newrelic.setCurrentRouteName(`/product/${id}`);
        newrelic.addPageAction('ProductViewed', { productId: id });
    }
}

// ── Search ─────────────────────────────────────────────────────────────
let searchTimer;
async function handleSearch(query) {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
        if (!query.trim()) { loadProducts(); return; }

        if (typeof newrelic !== 'undefined') {
            newrelic.setCurrentRouteName('/search');
            newrelic.addPageAction('ProductSearch', { query, queryLength: query.length });
        }

        try {
            const res  = await fetch(`${API}/api/products?search=${encodeURIComponent(query)}`);
            const data = await res.json();
            renderProducts(data);
            if (data.length === 0 && typeof newrelic !== 'undefined') {
                newrelic.addPageAction('SearchZeroResults', { query });
            }
        } catch (err) {
            if (typeof newrelic !== 'undefined') newrelic.noticeError(err, { context: 'search', query });
        }
    }, 300);
}

// ── Categories ─────────────────────────────────────────────────────────
async function loadCategories() {
    try {
        const res  = await fetch(`${API}/api/products/categories`);
        const cats = await res.json();
        const div  = document.getElementById('categories');
        div.innerHTML = `<button class="active" onclick="setActiveCategory(this); loadProducts()">All</button>` +
            cats.map(c => `<button onclick="setActiveCategory(this); loadProducts('${c}')">${c}</button>`).join('');
    } catch (err) {
        if (typeof newrelic !== 'undefined') newrelic.noticeError(err, { context: 'loadCategories' });
    }
}

function setActiveCategory(btn) {
    document.querySelectorAll('.categories button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
}

// ── Cart ───────────────────────────────────────────────────────────────
async function addToCart(productId, name, price, category) {
    try {
        const res = await fetch(`${API}/api/cart/add`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ productId, quantity: 1 })
        });
        if (!res.ok) throw new Error('Failed to add to cart');

        cart.push({ productId, name, price: Number(price), category, quantity: 1 });
        document.getElementById('cart-count').textContent = cart.length;
        showStatus(`Added "${name}" to cart`, 'success');

        if (typeof newrelic !== 'undefined') {
            newrelic.addPageAction('CartAdd', { productId, productName: name, category, price });
        }
    } catch (err) {
        showStatus('Failed to add item to cart', 'error');
        if (typeof newrelic !== 'undefined') newrelic.noticeError(err, { context: 'addToCart', productId });
    }
}

function showCart() {
    if (typeof newrelic !== 'undefined') newrelic.setCurrentRouteName('/cart');

    const total = cart.reduce((s, i) => s + i.price * i.quantity, 0);
    const modal = document.getElementById('modal-container');
    modal.innerHTML = `
        <div class="modal-overlay" onclick="if(event.target===this)closeModal()">
            <div class="modal">
                <h2>🛒 Your Cart (${cart.length} items)</h2>
                ${cart.length === 0 ? '<p style="color:#718096">Your cart is empty.</p>' :
                    cart.map(i => `
                        <div class="cart-item">
                            <span>${i.name} x${i.quantity}</span>
                            <span>$${(i.price * i.quantity).toFixed(2)}</span>
                        </div>`).join('') +
                    `<div class="cart-total">Total: $${total.toFixed(2)}</div>
                     <button class="checkout-btn" onclick="checkout()">Checkout</button>`
                }
                <button onclick="closeModal()" style="margin-top:1rem;width:100%;padding:0.6rem;border:1px solid #ddd;border-radius:4px;cursor:pointer;background:white">Close</button>
            </div>
        </div>`;
}

async function checkout() {
    if (cart.length === 0) return;
    const email = 'demo@techshop.com';

    if (typeof newrelic !== 'undefined') {
        newrelic.setCurrentRouteName('/checkout');
        newrelic.interaction().setAttribute('cartSize', cart.length).save();
    }

    try {
        const res = await fetch(`${API}/api/orders/checkout`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json', 'X-Session-Id': sessionId() },
            body:    JSON.stringify({
                customerEmail: email,
                items: cart.map(i => ({ productId: i.productId, quantity: i.quantity }))
            })
        });

        if (!res.ok) throw new Error(`Checkout failed: ${res.status}`);
        const order = await res.json();

        cart.length = 0;
        document.getElementById('cart-count').textContent = 0;
        closeModal();
        showStatus(`✅ Order ${order.orderNumber} placed! Total: $${Number(order.total).toFixed(2)}`, 'success');

        if (typeof newrelic !== 'undefined') {
            newrelic.addPageAction('CheckoutComplete', {
                orderNumber: order.orderNumber,
                orderTotal:  order.total,
                itemCount:   order.items?.length || cart.length
            });
        }
    } catch (err) {
        showStatus('Checkout failed — see console for details', 'error');
        if (typeof newrelic !== 'undefined') newrelic.noticeError(err, { context: 'checkout' });
    }
}

// ── Chaos toggles ──────────────────────────────────────────────────────
async function toggleChaos(flag) {
    chaosFlags[flag] = !chaosFlags[flag];
    const btn = document.getElementById(`btn-${flag}`);
    btn.classList.toggle('active', chaosFlags[flag]);

    // For cardinality bomb, hit the dedicated endpoint
    if (flag === 'cardinality-bomb' && chaosFlags[flag]) {
        fetch(`${API}/api/cardinality-demo/burst?count=200`, { method: 'POST' })
            .then(r => r.json())
            .then(d => showStatus(`Cardinality burst: ${d.eventsSimulated} events, mode=${d.mode}`, 'info'))
            .catch(() => {});
    }

    document.getElementById('chaos-status').textContent =
        Object.entries(chaosFlags).filter(([,v]) => v).map(([k]) => k).join(', ') || '';

    try {
        await fetch(`${API}/api/chaos`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ flag, enabled: chaosFlags[flag] })
        });
    } catch (_) {}

    if (typeof newrelic !== 'undefined') {
        newrelic.addPageAction('ChaosToggle', { flag, enabled: chaosFlags[flag] });
    }
}

// ── Helpers ────────────────────────────────────────────────────────────
function closeModal() { document.getElementById('modal-container').innerHTML = ''; }

function showStatus(msg, type = 'info') {
    const area = document.getElementById('status-area');
    area.innerHTML = `<div class="status ${type}">${msg}</div>`;
    setTimeout(() => { area.innerHTML = ''; }, 4000);
}

function sessionId() {
    if (!window._sid) window._sid = Math.random().toString(36).substring(2, 10);
    return window._sid;
}
