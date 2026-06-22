/* =========================================================================
   BORMAK BÜRO MAKİNELERİ PORTAL - SPA APP LOGIC
   ========================================================================= */

// Global State
let currentUser = null;
let currentTab = 'tab-overview';
let activeCustomerId = null;
let exchangeRateUsd = 0.0;

const API_BASE = ''; // Root relative since server serves public static files

// Wait for DOM to load
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

// Initialize Application
function initApp() {
    setupEventListeners();
    checkSession();
    initPdfWizard();
}

// Check if user is already logged in (local storage cache)
function checkSession() {
    const cached = localStorage.getItem('bormak_user');
    if (cached) {
        currentUser = JSON.parse(cached);
        loginSuccess(currentUser);
    } else {
        showLogin();
    }
}

function showLogin() {
    document.getElementById('login-container').classList.add('active');
    document.getElementById('app-container').classList.add('hidden');
}

function loginSuccess(user) {
    currentUser = user;
    localStorage.setItem('bormak_user', JSON.stringify(user));
    
    // Set user info in sidebar
    document.getElementById('current-user-name').textContent = user.fullName;
    document.getElementById('current-user-role').textContent = user.role === 'ADMIN' ? 'YÖNETİCİ' : 'PERSONEL';
    
    // Role-based visibility
    if (user.role === 'ADMIN') {
        document.querySelectorAll('.admin-only').forEach(el => el.classList.remove('hidden'));
    } else {
        document.querySelectorAll('.admin-only').forEach(el => el.classList.add('hidden'));
    }
    
    // Hide login, show app
    document.getElementById('login-container').classList.remove('active');
    document.getElementById('app-container').classList.remove('hidden');
    
    // Load initial global data
    fetchRates();
    loadDashboardStats();
    
    // Go to overview
    switchTab('tab-overview');
}

// ----------------------------------------------------
// ROUTING / TAB SYSTEM
// ----------------------------------------------------
function switchTab(tabId) {
    currentTab = tabId;
    
    // Update sidebar navigation active state
    document.querySelectorAll('.nav-item').forEach(item => {
        if (item.getAttribute('data-tab') === tabId) {
            item.classList.add('active');
        } else {
            item.classList.remove('active');
        }
    });
    
    // Switch active tab pane
    document.querySelectorAll('.tab-pane').forEach(pane => {
        if (pane.id === tabId) {
            pane.classList.add('active');
        } else {
            pane.classList.remove('active');
        }
    });
    
    // Set Page Title in Header
    const activeItem = document.querySelector(`.nav-item[data-tab="${tabId}"]`);
    if (activeItem) {
        document.getElementById('header-page-title').textContent = activeItem.textContent.trim();
    }
    
    // Load data specific to tab
    loadTabSpecificData(tabId);
}

function loadTabSpecificData(tabId) {
    if (tabId === 'tab-overview') {
        loadDashboardStats();
    } else if (tabId === 'tab-warehouse') {
        loadWarehouseData();
    } else if (tabId === 'tab-handovers') {
        loadHandoversData();
    } else if (tabId === 'tab-employees') {
        loadEmployeesData();
    } else if (tabId === 'tab-customers') {
        loadCustomersData();
    } else if (tabId === 'tab-machines') {
        loadMachinesData();
    } else if (tabId === 'tab-contracts') {
        loadContractsData();
    } else if (tabId === 'tab-toner-analysis') {
        loadTonerAnalysisData();
    } else if (tabId === 'tab-stock-history') {
        loadStockHistoryData();
    } else if (tabId === 'tab-yearly-report') {
        loadYearlyReportData();
    }
}

// ----------------------------------------------------
// EVENT LISTENERS & FORM HANDLERS
// ----------------------------------------------------
function setupEventListeners() {
    // Sidebar navigation clicks
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            const tab = item.getAttribute('data-tab');
            switchTab(tab);
        });
    });
    
    // Login Form Submit
    document.getElementById('login-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('login-username').value.trim();
        const password = document.getElementById('login-password').value;
        const errDiv = document.getElementById('login-error');
        
        errDiv.classList.add('hidden');
        
        try {
            const response = await fetch(`${API_BASE}/api/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const resData = await response.json();
            if (resData.status === 'success') {
                loginSuccess(resData.data);
            } else {
                errDiv.textContent = resData.message || 'Giriş Başarısız!';
                errDiv.classList.remove('hidden');
            }
        } catch (error) {
            errDiv.textContent = 'Sunucu bağlantı hatası!';
            errDiv.classList.remove('hidden');
        }
    });
    
    // Logout Button
    document.getElementById('btn-logout').addEventListener('click', () => {
        localStorage.removeItem('bormak_user');
        currentUser = null;
        showLogin();
    });
    
    // Form Product Add
    document.getElementById('form-add-product').addEventListener('submit', async (e) => {
        e.preventDefault();
        const stockCode = document.getElementById('prod-code').value.trim();
        const name = document.getElementById('prod-name').value.trim();
        const supplierName = document.getElementById('prod-supplier').value.trim();
        const initialQty = parseInt(document.getElementById('prod-qty').value) || 0;
        
        try {
            const res = await apiCall('/api/products', 'POST', { stockCode, name, supplierName, initialQty });
            alert(res.message);
            document.getElementById('form-add-product').reset();
            loadWarehouseData();
        } catch (err) {
            alert(err.message || 'Ürün kaydedilemedi.');
        }
    });
    
    // Form Warehouse Entry
    document.getElementById('form-warehouse-entry').addEventListener('submit', async (e) => {
        e.preventDefault();
        const productId = parseInt(document.getElementById('select-entry-product').value);
        const qty = parseInt(document.getElementById('entry-qty').value);
        
        try {
            const res = await apiCall('/api/products/entry', 'POST', { productId, qty });
            alert(res.message);
            document.getElementById('form-warehouse-entry').reset();
            loadWarehouseData();
        } catch (err) {
            alert(err.message || 'Depo girişi kaydedilemedi.');
        }
    });

    // Edit Product modal submit
    document.getElementById('form-edit-product').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('edit-prod-id').value;
        const stockCode = document.getElementById('edit-prod-code').value.trim();
        const name = document.getElementById('edit-prod-name').value.trim();
        const supplierName = document.getElementById('edit-prod-supplier').value.trim();
        
        try {
            const res = await apiCall(`/api/products/${id}`, 'PUT', { stockCode, name, supplierName });
            alert(res.message);
            closeModal('modal-edit-product');
            loadWarehouseData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Form Handover Transfer
    document.getElementById('form-handover-transfer').addEventListener('submit', async (e) => {
        e.preventDefault();
        const productId = parseInt(document.getElementById('select-handover-product').value);
        const employeeId = parseInt(document.getElementById('select-handover-employee').value);
        const qty = parseInt(document.getElementById('handover-qty').value);
        const description = document.getElementById('handover-desc').value.trim();
        
        try {
            const res = await apiCall('/api/employees/transfer', 'POST', { productId, employeeId, qty, description });
            alert(res.message);
            document.getElementById('form-handover-transfer').reset();
            loadHandoversData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Form Employee Add
    document.getElementById('form-add-employee').addEventListener('submit', async (e) => {
        e.preventDefault();
        const employeeCode = document.getElementById('emp-code').value.trim();
        const firstName = document.getElementById('emp-firstname').value.trim();
        const lastName = document.getElementById('emp-lastname').value.trim();
        
        try {
            const res = await apiCall('/api/employees', 'POST', { employeeCode, firstName, lastName });
            alert(res.message);
            document.getElementById('form-add-employee').reset();
            loadEmployeesData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Edit Employee modal submit
    document.getElementById('form-edit-employee').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('edit-emp-id').value;
        const employeeCode = document.getElementById('edit-emp-code').value.trim();
        const firstName = document.getElementById('edit-emp-firstname').value.trim();
        const lastName = document.getElementById('edit-emp-lastname').value.trim();
        
        try {
            const res = await apiCall(`/api/employees/${id}`, 'PUT', { employeeCode, firstName, lastName });
            alert(res.message);
            closeModal('modal-edit-employee');
            loadEmployeesData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Search input event filters
    setupSearchFilters();

    // Form Customer Add
    document.getElementById('form-add-customer').addEventListener('submit', async (e) => {
        e.preventDefault();
        const customerCode = document.getElementById('cust-code').value.trim();
        const companyName = document.getElementById('cust-name').value.trim();
        const responsibleIdVal = document.getElementById('cust-responsible').value;
        const responsibleId = responsibleIdVal ? parseInt(responsibleIdVal) : null;
        const businessModel = document.getElementById('cust-business').value;
        const unitPrice = parseFloat(document.getElementById('cust-price').value) || 0.0;
        
        try {
            const res = await apiCall('/api/customers', 'POST', { customerCode, companyName, responsibleId, businessModel, unitPrice });
            alert(res.message);
            document.getElementById('form-add-customer').reset();
            loadCustomersData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Edit Customer Modal Submit
    document.getElementById('form-edit-customer').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('edit-cust-id').value;
        const customerCode = document.getElementById('edit-cust-code').value.trim();
        const companyName = document.getElementById('edit-cust-name').value.trim();
        const responsibleIdVal = document.getElementById('edit-cust-responsible').value;
        const responsibleId = responsibleIdVal ? parseInt(responsibleIdVal) : null;
        const businessModel = document.getElementById('edit-cust-business').value;
        const unitPrice = parseFloat(document.getElementById('edit-cust-price').value) || 0.0;
        
        try {
            const res = await apiCall(`/api/customers/${id}`, 'PUT', { customerCode, companyName, responsibleId, businessModel, unitPrice });
            alert(res.message);
            closeModal('modal-edit-customer');
            loadCustomersData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Customer Delivery Source radio change handlers
    document.querySelectorAll('input[name="delivery-source"]').forEach(radio => {
        radio.addEventListener('change', (e) => {
            const val = e.target.value;
            const empGrp = document.getElementById('group-delivery-employee');
            if (val === 'employee') {
                empGrp.classList.remove('hidden');
                document.getElementById('select-delivery-emp').setAttribute('required', 'required');
            } else {
                empGrp.classList.add('hidden');
                document.getElementById('select-delivery-emp').removeAttribute('required');
            }
            populateDeliveryProductsDropdown();
        });
    });
    
    // Select delivery employee change loading
    document.getElementById('select-delivery-emp').addEventListener('change', () => {
        populateDeliveryProductsDropdown();
    });

    // Customer Delivery Submit
    document.getElementById('form-customer-delivery').addEventListener('submit', async (e) => {
        e.preventDefault();
        const productId = parseInt(document.getElementById('select-delivery-product').value);
        const customerId = parseInt(document.getElementById('select-delivery-cust').value);
        const qty = parseInt(document.getElementById('delivery-qty').value);
        const description = document.getElementById('delivery-desc').value.trim();
        const fromWarehouse = document.querySelector('input[name="delivery-source"]:checked').value === 'warehouse';
        
        const empVal = document.getElementById('select-delivery-emp').value;
        const employeeId = empVal ? parseInt(empVal) : null;

        try {
            const res = await apiCall('/api/customers/delivery', 'POST', { productId, customerId, qty, description, fromWarehouse, employeeId });
            alert(res.message);
            document.getElementById('form-customer-delivery').reset();
            // Default radio triggers hidden
            document.getElementById('group-delivery-employee').classList.add('hidden');
            loadCustomersData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Modal Customer Detail tabs switcher
    document.querySelectorAll('.btn-sub-tab').forEach(btn => {
        btn.addEventListener('click', () => {
            const target = btn.getAttribute('data-subtab');
            
            document.querySelectorAll('.btn-sub-tab').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            
            document.querySelectorAll('.sub-tab-pane').forEach(p => p.classList.remove('active'));
            document.getElementById(target).classList.add('active');
        });
    });

    // Detail Meter Reading Add form
    document.getElementById('form-detail-add-meter').addEventListener('submit', async (e) => {
        e.preventDefault();
        const machineId = parseInt(document.getElementById('detail-meter-machine').value);
        const meterValue = parseInt(document.getElementById('detail-meter-val').value);
        
        try {
            const res = await apiCall(`/api/customer-machines/${machineId}/meter-readings`, 'POST', { meterValue });
            alert(res.message);
            document.getElementById('form-detail-add-meter').reset();
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    });

    // Detail Toner type select details update
    document.getElementById('detail-toner-type').addEventListener('change', () => {
        recalculateTonerDetails();
    });
    
    // Detail Toner grams change recalc
    document.getElementById('detail-toner-grams').addEventListener('input', () => {
        recalculateTonerDetails();
    });

    // Detail Toner delivery add form
    document.getElementById('form-detail-add-toner').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tonerTypeId = parseInt(document.getElementById('detail-toner-type').value);
        const grams = parseInt(document.getElementById('detail-toner-grams').value);
        const notes = document.getElementById('detail-toner-notes').value.trim();
        
        try {
            const res = await apiCall(`/api/customers/${activeCustomerId}/toners`, 'POST', { tonerTypeId, grams, notes });
            alert(res.message);
            document.getElementById('form-detail-add-toner').reset();
            document.getElementById('detail-toner-grams').value = 450;
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    });

    // Detail Add Service Expense form
    document.getElementById('form-detail-add-service').addEventListener('submit', async (e) => {
        e.preventDefault();
        const description = document.getElementById('detail-service-desc').value.trim();
        const amount = parseFloat(document.getElementById('detail-service-amount').value) || 0.0;
        
        try {
            const res = await apiCall(`/api/customers/${activeCustomerId}/services`, 'POST', { description, amount });
            alert(res.message);
            document.getElementById('form-detail-add-service').reset();
            document.getElementById('detail-service-amount').value = 100;
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    });

    // Detail Dispatch source radio change listener
    document.querySelectorAll('input[name="dispatch-source"]').forEach(radio => {
        radio.addEventListener('change', (e) => {
            const val = e.target.value;
            const empGrp = document.getElementById('detail-dispatch-emp-group');
            if (val === 'employee') {
                empGrp.classList.remove('hidden');
                document.getElementById('detail-dispatch-emp').setAttribute('required', 'required');
            } else {
                empGrp.classList.add('hidden');
                document.getElementById('detail-dispatch-emp').removeAttribute('required');
            }
            populateDetailDispatchProductsDropdown();
        });
    });

    // Detail Dispatch employee select loading
    document.getElementById('detail-dispatch-emp').addEventListener('change', () => {
        populateDetailDispatchProductsDropdown();
    });

    // Detail Dispatch product select cost update
    document.getElementById('detail-dispatch-prod').addEventListener('change', () => {
        const sel = document.getElementById('detail-dispatch-prod');
        const price = sel.options[sel.selectedIndex]?.getAttribute('data-cost') || 0;
        document.getElementById('detail-dispatch-cost').value = parseFloat(price).toFixed(2);
    });

    // Detail Dispatch submit
    document.getElementById('form-detail-add-dispatch').addEventListener('submit', async (e) => {
        e.preventDefault();
        const machineId = parseInt(document.getElementById('detail-dispatch-machine').value);
        const productId = parseInt(document.getElementById('detail-dispatch-prod').value);
        const qty = parseInt(document.getElementById('detail-dispatch-qty').value);
        const description = document.getElementById('detail-dispatch-desc').value.trim();
        const cost = parseFloat(document.getElementById('detail-dispatch-cost').value) || 0.0;
        const fromWarehouse = document.querySelector('input[name="dispatch-source"]:checked').value === 'warehouse';
        
        const empVal = document.getElementById('detail-dispatch-emp').value;
        const employeeId = empVal ? parseInt(empVal) : null;

        try {
            const res = await apiCall(`/api/customers/${activeCustomerId}/dispatches`, 'POST', { machineId, productId, qty, description, cost, fromWarehouse, employeeId });
            alert(res.message);
            document.getElementById('form-detail-add-dispatch').reset();
            document.getElementById('detail-dispatch-emp-group').classList.add('hidden');
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    });

    // Form Machine Add
    document.getElementById('form-add-machine').addEventListener('submit', async (e) => {
        e.preventDefault();
        const customerId = parseInt(document.getElementById('mach-cust').value);
        const machineName = document.getElementById('mach-name').value.trim();
        const serialNumber = document.getElementById('mach-serial').value.trim();
        const initialMeter = parseInt(document.getElementById('mach-initial-meter').value) || 0;
        const initialMeterDate = document.getElementById('mach-initial-date').value;
        const installationDate = document.getElementById('mach-install-date').value;
        const ownershipType = document.getElementById('mach-ownership').value;

        try {
            const res = await apiCall('/api/customer-machines', 'POST', { customerId, machineName, serialNumber, initialMeter, initialMeterDate, installationDate, ownershipType });
            alert(res.message);
            document.getElementById('form-add-machine').reset();
            setInstallDatesToday();
            loadMachinesData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Form Machine Edit modal submit
    document.getElementById('form-edit-machine').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('edit-mach-id').value;
        const customerId = parseInt(document.getElementById('edit-mach-cust').value);
        const machineName = document.getElementById('edit-mach-name').value.trim();
        const serialNumber = document.getElementById('edit-mach-serial').value.trim();
        const initialMeter = parseInt(document.getElementById('edit-mach-initial-meter').value) || 0;
        const currentMeter = parseInt(document.getElementById('edit-mach-current-meter').value) || 0;
        const initialMeterDate = document.getElementById('edit-mach-initial-date').value;
        const installationDate = document.getElementById('edit-mach-install-date').value;
        const ownershipType = document.getElementById('edit-mach-ownership').value;

        try {
            const res = await apiCall(`/api/customer-machines/${id}`, 'PUT', { customerId, machineName, serialNumber, initialMeter, currentMeter, initialMeterDate, installationDate, ownershipType });
            alert(res.message);
            closeModal('modal-edit-machine');
            loadMachinesData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Form Contract Device registration
    document.getElementById('form-contract-device').addEventListener('submit', async (e) => {
        e.preventDefault();
        const serialNumber = document.getElementById('contract-serial').value.trim();
        const brandModel = document.getElementById('contract-model').value.trim();
        const customerId = parseInt(document.getElementById('contract-cust').value);
        const ownershipType = document.getElementById('contract-ownership').value;
        const businessModel = document.getElementById('contract-business').value;

        try {
            const res = await apiCall('/api/contracts/devices', 'POST', { serialNumber, brandModel, customerId, ownershipType, businessModel });
            alert(res.message);
            document.getElementById('form-contract-device').reset();
            loadContractsData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Contract operations select device change loading info
    document.getElementById('select-op-device').addEventListener('change', (e) => {
        const opt = e.target.options[e.target.selectedIndex];
        if (!opt || !opt.value) {
            document.getElementById('op-device-info').innerHTML = 'Firma: - | Sistem: - | Mülkiyet: -';
            return;
        }
        const company = opt.getAttribute('data-company');
        const bm = opt.getAttribute('data-bm');
        const own = opt.getAttribute('data-own');

        document.getElementById('op-device-info').innerHTML = `Firma: <strong>${company}</strong> | Sistem: <strong>${bm}</strong> | Mülkiyet: <strong>${own}</strong>`;

        // Auto display settings based on contract model
        const salePriceGrp = document.getElementById('op-sale-price-group');
        if (bm.includes('Normal')) {
            salePriceGrp.classList.remove('hidden');
            document.getElementById('op-sale-price').disabled = false;
        } else {
            salePriceGrp.classList.add('hidden');
            document.getElementById('op-sale-price').value = '0.00';
            document.getElementById('op-sale-price').disabled = true;
        }
    });

    // Contract operation type selection toggler
    document.getElementById('select-op-type').addEventListener('change', (e) => {
        const val = e.target.value;
        const tonFields = document.getElementById('op-toner-fields');
        const partFields = document.getElementById('op-sparepart-fields');

        if (val === 'toner') {
            tonFields.classList.remove('hidden');
            partFields.classList.add('hidden');
        } else {
            tonFields.classList.add('hidden');
            partFields.classList.remove('hidden');
            populateContractSparePartsDropdown();
        }
    });

    // Contract Operation Panel Spare Part source radio change
    document.getElementById('select-op-source').addEventListener('change', (e) => {
        const val = e.target.value;
        const empGrp = document.getElementById('op-emp-group');
        if (val === 'employee') {
            empGrp.classList.remove('hidden');
            document.getElementById('select-op-emp').setAttribute('required', 'required');
        } else {
            empGrp.classList.add('hidden');
            document.getElementById('select-op-emp').removeAttribute('required');
        }
        populateContractSparePartsDropdown();
    });

    // Contract operations select employee change reload parts
    document.getElementById('select-op-emp').addEventListener('change', () => {
        populateContractSparePartsDropdown();
    });

    // Contract operations select product cost auto value
    document.getElementById('select-op-prod').addEventListener('change', () => {
        const sel = document.getElementById('select-op-prod');
        const price = sel.options[sel.selectedIndex]?.getAttribute('data-cost') || 0;
        document.getElementById('op-material-cost').value = parseFloat(price).toFixed(2);
    });

    // Complete contract operation submit
    document.getElementById('form-contract-op').addEventListener('submit', async (e) => {
        e.preventDefault();
        const machineId = parseInt(document.getElementById('select-op-device').value);
        const opTypeVal = document.getElementById('select-op-type').value;
        
        // Find which customer this machine belongs to
        const opt = document.getElementById('select-op-device').options[document.getElementById('select-op-device').selectedIndex];
        const custId = parseInt(opt.getAttribute('data-custid'));

        let body = { machineId, fromWarehouse: true };

        if (opTypeVal === 'toner') {
            const tonerTypeId = parseInt(document.getElementById('select-op-toner').value);
            const grams = parseInt(document.getElementById('select-op-grams').value);
            const notes = 'Cihaz Dolumu: ' + document.getElementById('select-op-toner').options[document.getElementById('select-op-toner').selectedIndex].text + ` (${grams}g)`;
            
            // Re-route to same logic as customer delivery but with cost
            try {
                const res = await apiCall(`/api/customers/${custId}/toners`, 'POST', { tonerTypeId, grams, notes });
                alert(res.message);
                document.getElementById('form-contract-op').reset();
                loadContractsData();
            } catch (err) {
                alert(err.message);
            }
            return;
        } else {
            // Spare Part Montajı
            const fromWarehouse = document.getElementById('select-op-source').value === 'warehouse';
            const empVal = document.getElementById('select-op-emp').value;
            const employeeId = empVal ? parseInt(empVal) : null;
            const productId = parseInt(document.getElementById('select-op-prod').value);
            const qty = parseInt(document.getElementById('op-prod-qty').value);
            const description = document.getElementById('op-material-desc').value.trim();
            const cost = parseFloat(document.getElementById('op-material-cost').value) || 0.0;

            try {
                const res = await apiCall(`/api/customers/${custId}/dispatches`, 'POST', { machineId, productId, qty, description, cost, fromWarehouse, employeeId });
                alert(res.message);
                document.getElementById('form-contract-op').reset();
                loadContractsData();
            } catch (err) {
                alert(err.message);
            }
        }
    });

    // Form Add Toner Type (Analysis Module)
    document.getElementById('form-add-toner-type').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tonerName = document.getElementById('toner-name').value.trim();
        const unitKgCost = parseFloat(document.getElementById('toner-cost-kg').value) || 0.0;
        const standardPageCapacity = parseInt(document.getElementById('toner-capacity').value) || 20000;

        try {
            const res = await apiCall('/api/toner-types', 'POST', { tonerName, unitKgCost, standardPageCapacity });
            alert(res.message);
            document.getElementById('form-add-toner-type').reset();
            loadTonerAnalysisData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Toner calc type select recalculation
    document.getElementById('select-calc-toner').addEventListener('change', (e) => {
        const opt = e.target.options[e.target.selectedIndex];
        if (!opt || !opt.value) {
            document.getElementById('calc-toner-info').innerHTML = 'Gram Maliyeti: - | Gram Başı Sayfa: -';
            return;
        }
        const costG = parseFloat(opt.getAttribute('data-costg')).toFixed(2);
        const pageG = parseFloat(opt.getAttribute('data-pageg')).toFixed(2);
        document.getElementById('calc-toner-info').innerHTML = `Gram Maliyeti: <strong>${costG} TL</strong> | Gram Başı Sayfa: <strong>${pageG}</strong>`;
        recalculateTonerCalcResult();
    });

    // Preset select recalculation
    document.getElementById('select-calc-preset').addEventListener('change', (e) => {
        const val = e.target.value;
        const manGroup = document.getElementById('calc-custom-grams-group');
        const manInput = document.getElementById('calc-custom-grams');

        if (val === 'custom') {
            manGroup.classList.remove('hidden');
            manInput.value = '';
            manInput.required = true;
            manInput.focus();
        } else {
            manGroup.classList.add('hidden');
            manInput.value = val;
            manInput.required = false;
        }
        recalculateTonerCalcResult();
    });

    // Custom grams input recalc
    document.getElementById('calc-custom-grams').addEventListener('input', () => {
        recalculateTonerCalcResult();
    });

    // Save toner filling form
    document.getElementById('form-toner-fill-calc').addEventListener('submit', async (e) => {
        e.preventDefault();
        const tonerTypeId = parseInt(document.getElementById('select-calc-toner').value);
        const presetVal = document.getElementById('select-calc-preset').value;
        
        let grams = 0;
        if (presetVal === 'custom') {
            grams = parseInt(document.getElementById('calc-custom-grams').value) || 0;
        } else {
            grams = parseInt(presetVal) || 0;
        }

        if (grams <= 0) {
            alert('Lütfen geçerli bir gramaj girin.');
            return;
        }

        try {
            const res = await apiCall('/api/toner-fillings', 'POST', { tonerTypeId, grams });
            alert(res.message);
            document.getElementById('form-toner-fill-calc').reset();
            document.getElementById('calc-custom-grams-group').classList.add('hidden');
            document.getElementById('calc-toner-info').innerHTML = 'Gram Maliyeti: - | Gram Başı Sayfa: -';
            document.getElementById('calc-result-cost').textContent = '0.00 TL';
            document.getElementById('calc-result-pages').textContent = '0 Sayfa';
            loadTonerAnalysisData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Toner Type Edit submit
    document.getElementById('form-edit-toner-type').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('edit-toner-id').value;
        const tonerName = document.getElementById('edit-toner-name').value.trim();
        const unitKgCost = parseFloat(document.getElementById('edit-toner-cost-kg').value) || 0.0;
        const standardPageCapacity = parseInt(document.getElementById('edit-toner-capacity').value) || 20000;

        try {
            const res = await apiCall(`/api/toner-types/${id}`, 'PUT', { tonerName, unitKgCost, standardPageCapacity });
            alert(res.message);
            closeModal('modal-edit-toner-type');
            loadTonerAnalysisData();
        } catch (err) {
            alert(err.message);
        }
    });

    // Filter Stock History on date values change
    document.getElementById('filter-history-start').addEventListener('change', () => loadStockHistoryData());
    document.getElementById('filter-history-end').addEventListener('change', () => loadStockHistoryData());

    // Export Stock History as Excel CSV
    document.getElementById('btn-history-export').addEventListener('click', () => {
        exportTableToCSV('Stok_Hareketi_Raporu.csv', 'table-stock-history');
    });

    // Yearly Report Year select reload
    document.getElementById('select-report-year').addEventListener('change', () => loadYearlyReportData());
    
    // Yearly Report Export CSV
    document.getElementById('btn-yearly-export').addEventListener('click', () => {
        const year = document.getElementById('select-report-year').value;
        exportTableToCSV(`Yillik_Kopya_Raporu_${year}.csv`, 'table-yearly-report');
    });
}

// Global API Helper
async function apiCall(endpoint, method = 'GET', body = null) {
    const config = {
        method,
        headers: { 'Content-Type': 'application/json' }
    };
    if (body) {
        config.body = JSON.stringify(body);
    }
    
    const response = await fetch(`${API_BASE}${endpoint}`, config);
    const data = await response.json();
    if (data.status === 'success') {
        return data;
    } else {
        throw new Error(data.message || 'Bir hata oluştu.');
    }
}

// ----------------------------------------------------
// FETCH TCMB EXCHANGE RATES
// ----------------------------------------------------
async function fetchRates() {
    try {
        const res = await apiCall('/api/currency/rates');
        const display = document.getElementById('tcmb-rates-display');
        if (res.data.loaded) {
            exchangeRateUsd = res.data.usd;
            display.textContent = `TCMB Kurlar - USD: ${res.data.usd.toFixed(4)} TL | EUR: ${res.data.eur.toFixed(4)} TL`;
        } else {
            display.textContent = 'TCMB Döviz Kurları Yüklenemedi';
        }
    } catch (error) {
        document.getElementById('tcmb-rates-display').textContent = 'Döviz kurları alınamadı';
    }
}

// ----------------------------------------------------
// TAB DATA LOADING LOGIC
// ----------------------------------------------------

// OVERVIEW
async function loadDashboardStats() {
    try {
        const res = await apiCall('/api/dashboard-summary');
        const data = res.data;
        
        document.getElementById('stat-warehouse-stock').textContent = `${data.totalWarehouseStock} Adet`;
        document.getElementById('stat-total-employees').textContent = `${data.totalEmployees} Kişi`;
        document.getElementById('stat-total-customers').textContent = `${data.totalCustomers} Firma`;
        document.getElementById('stat-critical-meters').textContent = `${data.criticalMeterCount} Cihaz`;
        
        // Manage warning banner
        const banner = document.getElementById('alert-banner');
        if (data.criticalMeterCount > 0) {
            banner.classList.remove('hidden');
            document.getElementById('alert-banner-text').textContent = `⚠️ Kritik Uyarı: Sayaç okuması 30 günü geçmiş veya hiç girilmemiş ${data.criticalMeterCount} adet müşteri cihazı bulunmaktadır!`;
            document.getElementById('stat-critical-card').classList.add('text-danger');
        } else {
            banner.classList.add('hidden');
            document.getElementById('stat-critical-card').classList.remove('text-danger');
        }
    } catch (err) {
        console.error(err);
    }
}

// WAREHOUSE
async function loadWarehouseData() {
    try {
        const res = await apiCall('/api/products');
        const products = res.data;
        
        const tbody = document.getElementById('tbody-products');
        tbody.innerHTML = '';
        
        const selectEntry = document.getElementById('select-entry-product');
        const selectTransfer = document.getElementById('select-handover-product');
        const selectDelivery = document.getElementById('select-delivery-product');
        const selectDispatch = document.getElementById('detail-dispatch-prod');
        const selectOpProd = document.getElementById('select-op-prod');
        
        selectEntry.innerHTML = '<option value="">Seçiniz...</option>';
        selectTransfer.innerHTML = '<option value="">Seçiniz...</option>';
        selectDelivery.innerHTML = '<option value="">Seçiniz...</option>';
        selectDispatch.innerHTML = '<option value="">Seçiniz...</option>';
        selectOpProd.innerHTML = '<option value="">Seçiniz...</option>';
        
        products.forEach(p => {
            // Populate table
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong>${p.stockCode}</strong></td>
                <td>${p.name}</td>
                <td>${p.supplierName || '-'}</td>
                <td>${p.warehouseQuantity} Adet</td>
                <td>
                    <button class="btn btn-secondary op-row-action" onclick="openEditProductModal(${p.id}, '${p.stockCode}', '${p.name}', '${p.supplierName}')">Düzenle</button>
                    <button class="btn btn-danger op-row-action" onclick="deleteProduct(${p.id}, '${p.name}')">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);
            
            // Populate dropdowns
            const opt = document.createElement('option');
            opt.value = p.id;
            opt.textContent = `${p.name} (${p.stockCode}) - Stok: ${p.warehouseQuantity}`;
            opt.setAttribute('data-qty', p.warehouseQuantity);
            opt.setAttribute('data-cost', 0); // Cost calculations later if needed
            
            selectEntry.appendChild(opt.cloneNode(true));
            if (p.warehouseQuantity > 0) {
                selectTransfer.appendChild(opt.cloneNode(true));
                selectOpProd.appendChild(opt.cloneNode(true));
            }
        });
        
        // Store products globally for easy client filters
        window.allProducts = products;
    } catch (err) {
        console.error(err);
    }
}

function openEditProductModal(id, code, name, supplier) {
    document.getElementById('edit-prod-id').value = id;
    document.getElementById('edit-prod-code').value = code;
    document.getElementById('edit-prod-name').value = name;
    document.getElementById('edit-prod-supplier').value = supplier === 'null' ? '' : supplier;
    openModal('modal-edit-product');
}

async function deleteProduct(id, name) {
    if (confirm(`"${name}" adlı ürünü silmek istediğinize emin misiniz?\nUYARI: Bu ürüne ait tüm stok hareketleri ve zimmetler silinecektir!`)) {
        try {
            const res = await apiCall(`/api/products/${id}`, 'DELETE');
            alert(res.message);
            loadWarehouseData();
        } catch (err) {
            alert(err.message);
        }
    }
}

// ZİMMETLER (HANDOVERS)
async function loadHandoversData() {
    try {
        const empRes = await apiCall('/api/employees');
        const employees = empRes.data;
        
        const selectEmp = document.getElementById('select-handover-employee');
        const selectDeliveryEmp = document.getElementById('select-delivery-emp');
        const selectDetailDispEmp = document.getElementById('detail-dispatch-emp');
        const selectOpEmp = document.getElementById('select-op-emp');
        
        selectEmp.innerHTML = '<option value="">Seçiniz...</option>';
        selectDeliveryEmp.innerHTML = '<option value="">Seçiniz...</option>';
        selectDetailDispEmp.innerHTML = '<option value="">Seçiniz...</option>';
        selectOpEmp.innerHTML = '<option value="">Seçiniz...</option>';
        
        employees.forEach(e => {
            const opt = document.createElement('option');
            opt.value = e.id;
            opt.textContent = e.fullName;
            selectEmp.appendChild(opt.cloneNode(true));
            selectDeliveryEmp.appendChild(opt.cloneNode(true));
            selectDetailDispEmp.appendChild(opt.cloneNode(true));
            selectOpEmp.appendChild(opt.cloneNode(true));
        });

        // Load Handover Cards
        const grid = document.getElementById('handover-cards-grid');
        grid.innerHTML = '';
        
        for (const e of employees) {
            const card = document.createElement('div');
            card.className = 'handover-card glass';
            card.setAttribute('data-name', e.fullName.toLowerCase());
            
            // Get employee zimmets
            const hRes = await apiCall(`/api/employees/${e.id}/handovers`);
            const handovers = hRes.data;
            
            let itemsHtml = '';
            if (handovers.length > 0) {
                handovers.forEach(h => {
                    itemsHtml += `
                        <div class="item-row">
                            <span>${h.productName}</span>
                            <strong>${h.quantity} Adet</strong>
                        </div>
                    `;
                });
            } else {
                itemsHtml = '<span class="empty-state">Zimmetinde ürün bulunmuyor.</span>';
            }
            
            card.innerHTML = `
                <h3>${e.fullName}</h3>
                <div class="code">Çalışan Kodu: ${e.employeeCode}</div>
                <div class="items-summary">
                    ${itemsHtml}
                </div>
            `;
            grid.appendChild(card);
        }
    } catch (err) {
        console.error(err);
    }
}

// EMPLOYEES
async function loadEmployeesData() {
    try {
        const res = await apiCall('/api/employees');
        const employees = res.data;
        
        const tbody = document.getElementById('tbody-employees');
        tbody.innerHTML = '';
        
        employees.forEach(e => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong>${e.employeeCode}</strong></td>
                <td>${e.firstName}</td>
                <td>${e.lastName}</td>
                <td>
                    <button class="btn btn-secondary op-row-action" onclick="openEditEmployeeModal(${e.id}, '${e.employeeCode}', '${e.firstName}', '${e.lastName}')">Düzenle</button>
                    <button class="btn btn-danger op-row-action" onclick="deleteEmployee(${e.id}, '${e.fullName}')">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

function openEditEmployeeModal(id, code, first, last) {
    document.getElementById('edit-emp-id').value = id;
    document.getElementById('edit-emp-code').value = code;
    document.getElementById('edit-emp-firstname').value = first;
    document.getElementById('edit-emp-lastname').value = last;
    openModal('modal-edit-employee');
}

async function deleteEmployee(id, name) {
    if (confirm(`"${name}" personelini silmek istediğinize emin misiniz?\nUYARI: Bu işlem personelin üzerindeki tüm zimmetleri silecektir!`)) {
        try {
            const res = await apiCall(`/api/employees/${id}`, 'DELETE');
            alert(res.message);
            loadEmployeesData();
        } catch (err) {
            alert(err.message);
        }
    }
}

// CUSTOMERS
async function loadCustomersData() {
    try {
        const custRes = await apiCall('/api/customers');
        const customers = custRes.data;
        window.allCustomers = customers;

        const empRes = await apiCall('/api/employees');
        const employees = empRes.data;

        // Populate customer form responsible selections
        const selectResp = document.getElementById('cust-responsible');
        const selectEditResp = document.getElementById('edit-cust-responsible');
        const selectMachCust = document.getElementById('mach-cust');
        const selectEditMachCust = document.getElementById('edit-mach-cust');
        const selectDeliveryCust = document.getElementById('select-delivery-cust');
        const selectContractCust = document.getElementById('contract-cust');
        
        selectResp.innerHTML = '<option value="">Seçiniz (Opsiyonel)</option>';
        selectEditResp.innerHTML = '<option value="">Seçiniz (Opsiyonel)</option>';
        selectMachCust.innerHTML = '<option value="">Seçiniz...</option>';
        selectEditMachCust.innerHTML = '<option value="">Seçiniz...</option>';
        selectDeliveryCust.innerHTML = '<option value="">Seçiniz...</option>';
        selectContractCust.innerHTML = '<option value="">Seçiniz...</option>';

        // Filter dropdown populate
        const filterResp = document.getElementById('filter-customer-responsible');
        filterResp.innerHTML = '<option value="">Tüm Personeller</option>';

        employees.forEach(e => {
            const opt = document.createElement('option');
            opt.value = e.id;
            opt.textContent = e.fullName;
            selectResp.appendChild(opt.cloneNode(true));
            selectEditResp.appendChild(opt.cloneNode(true));
            filterResp.appendChild(opt.cloneNode(true));
        });

        // Load customers in list and form options
        const tbody = document.getElementById('tbody-customers');
        tbody.innerHTML = '';

        customers.forEach(c => {
            // Table row
            const tr = document.createElement('tr');
            tr.setAttribute('data-id', c.id);
            tr.className = 'clickable-row';
            
            // Double click opens detail
            tr.addEventListener('dblclick', () => {
                openCustomerDetailModal(c.id, c.companyName, c.customerCode);
            });

            let sysDisp = 'Kopya Başı';
            if (c.businessModel === 'MALZEME_KARSILIGI') sysDisp = 'Malzeme Karşılığı';
            else if (c.businessModel === 'NORMAL') sysDisp = 'Normal Sistem';

            let statusClass = 'text-success';
            if (c.meterStatus.includes('KRİTİK')) statusClass = 'text-danger';
            else if (c.meterStatus.includes('Yok')) statusClass = 'text-muted';

            tr.innerHTML = `
                <td><strong>${c.customerCode}</strong></td>
                <td>${c.companyName}</td>
                <td>${sysDisp}</td>
                <td>${c.responsibleName}</td>
                <td class="${statusClass}">${c.meterStatus}</td>
                <td>
                    <button class="btn btn-primary op-row-action" onclick="openCustomerDetailModal(${c.id}, '${c.companyName}', '${c.customerCode}')">Detaylar</button>
                    <button class="btn btn-secondary op-row-action" onclick="openEditCustomerModal(${c.id}, '${c.customerCode}', '${c.companyName}', ${c.responsibleId}, '${c.businessModel}', ${c.unitPrice})">Düzenle</button>
                    <button class="btn btn-danger op-row-action" onclick="deleteCustomer(${c.id}, '${c.companyName}')">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);

            // Dropdowns
            const opt = document.createElement('option');
            opt.value = c.id;
            opt.textContent = c.companyName;
            selectMachCust.appendChild(opt.cloneNode(true));
            selectEditMachCust.appendChild(opt.cloneNode(true));
            selectDeliveryCust.appendChild(opt.cloneNode(true));
            selectContractCust.appendChild(opt.cloneNode(true));
        });
    } catch (err) {
        console.error(err);
    }
}

function openEditCustomerModal(id, code, name, respId, bm, price) {
    document.getElementById('edit-cust-id').value = id;
    document.getElementById('edit-cust-code').value = code;
    document.getElementById('edit-cust-name').value = name;
    document.getElementById('edit-cust-responsible').value = respId || '';
    document.getElementById('edit-cust-business').value = bm;
    document.getElementById('edit-cust-price').value = price;
    openModal('modal-edit-customer');
}

async function deleteCustomer(id, name) {
    if (confirm(`"${name}" müşterisini silmek istediğinize emin misiniz?\nUYARI: Bu müşteriye ait tüm cihazlar, sayaçlar, toner teslimatları ve parça çıkışları silinecektir!`)) {
        try {
            const res = await apiCall(`/api/customers/${id}`, 'DELETE');
            alert(res.message);
            loadCustomersData();
        } catch (err) {
            alert(err.message);
        }
    }
}

// Helper to load delivery products based on selected source (Warehouse vs Employee Zimmet)
async function populateDeliveryProductsDropdown() {
    const isWarehouse = document.querySelector('input[name="delivery-source"]:checked').value === 'warehouse';
    const select = document.getElementById('select-delivery-product');
    select.innerHTML = '<option value="">Seçiniz...</option>';

    if (isWarehouse) {
        if (window.allProducts) {
            window.allProducts.forEach(p => {
                if (p.warehouseQuantity > 0) {
                    const opt = document.createElement('option');
                    opt.value = p.id;
                    opt.textContent = `${p.name} (${p.stockCode}) - Depo: ${p.warehouseQuantity}`;
                    select.appendChild(opt);
                }
            });
        }
    } else {
        const empId = document.getElementById('select-delivery-emp').value;
        if (!empId) return;
        
        try {
            const res = await apiCall(`/api/employees/${empId}/handovers`);
            const handovers = res.data;
            handovers.forEach(h => {
                const opt = document.createElement('option');
                opt.value = h.productId;
                opt.textContent = `${h.productName} (${h.stockCode}) - Zimmet: ${h.quantity}`;
                select.appendChild(opt);
            });
        } catch (err) {
            console.error(err);
        }
    }
}

// ----------------------------------------------------
// CUSTOMER DETAIL MODAL FUNCTIONS
// ----------------------------------------------------
async function openCustomerDetailModal(id, name, code) {
    activeCustomerId = id;
    document.getElementById('detail-customer-name').textContent = name;
    document.getElementById('detail-customer-code').textContent = `Müşteri Kodu: ${code}`;
    
    // Set default active horizontal tab
    document.querySelectorAll('.btn-sub-tab').forEach(b => b.classList.remove('active'));
    document.querySelector('.btn-sub-tab[data-subtab="subtab-delivery-history"]').classList.add('active');
    
    document.querySelectorAll('.sub-tab-pane').forEach(p => p.classList.remove('active'));
    document.getElementById('subtab-delivery-history').classList.add('active');
    
    // Open modal
    openModal('modal-customer-detail');
    
    // Fetch and populate details
    loadCustomerDetailModal(id);
}

async function loadCustomerDetailModal(id) {
    try {
        const res = await apiCall(`/api/customers/${id}/details`);
        const payload = res.data;

        // Display stats cards
        const financials = payload.financials;
        document.getElementById('detail-stat-copies').textContent = financials.totalCopies.toLocaleString('tr-TR');
        
        // Dollar conversions if loaded
        let usdVal = '';
        if (exchangeRateUsd > 0) {
            usdVal = ` ($${(financials.totalRevenue / exchangeRateUsd).toFixed(2)})`;
        }
        document.getElementById('detail-stat-revenue').textContent = financials.totalRevenue.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' + usdVal;
        
        let usdExp = '';
        if (exchangeRateUsd > 0) {
            usdExp = ` ($${(financials.totalExpenses / exchangeRateUsd).toFixed(2)})`;
        }
        document.getElementById('detail-stat-expenses').textContent = financials.totalExpenses.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' + usdExp;
        
        let usdProfit = '';
        if (exchangeRateUsd > 0) {
            usdProfit = ` ($${(financials.netProfit / exchangeRateUsd).toFixed(2)})`;
        }
        const profitEl = document.getElementById('detail-stat-profit');
        profitEl.textContent = financials.netProfit.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' + usdProfit;
        
        // Colors for net profit
        const profitCard = document.getElementById('detail-profit-card');
        if (financials.netProfit > 0) {
            profitCard.style.borderBottomColor = 'var(--success-color)';
            profitEl.className = 'text-success';
        } else if (financials.netProfit < 0) {
            profitCard.style.borderBottomColor = 'var(--danger-color)';
            profitEl.className = 'text-danger';
        } else {
            profitCard.style.borderBottomColor = 'var(--text-muted)';
            profitEl.className = 'text-muted';
        }

        // Display delivery history table
        const tbodyHistory = document.getElementById('tbody-detail-history');
        tbodyHistory.innerHTML = '';
        payload.history.forEach(h => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${h.date}</td>
                <td>${h.type}</td>
                <td>${h.handler}</td>
                <td><strong>${h.stockCode}</strong></td>
                <td>${h.productName}</td>
                <td>${h.quantity}</td>
                <td>${h.desc}</td>
            `;
            tbodyHistory.appendChild(tr);
        });

        // Hide/Show Meter tracking tab based on customer business model
        const meterBtn = document.getElementById('subtab-meter-btn');
        if (payload.businessModel === 'KOPYA_BASI') {
            meterBtn.classList.remove('hidden');
            loadMeterTrackingSubtab(id);
        } else {
            meterBtn.classList.add('hidden');
        }

        // Load other subtabs
        loadTonerDeliverySubtab(id);
        loadServicesSubtab(id);
        loadMaterialDispatchesSubtab(id);

    } catch (err) {
        console.error(err);
    }
}

// Subtab: Meter readings
async function loadMeterTrackingSubtab(customerId) {
    try {
        // Load machines dropdown in the form
        const machRes = await apiCall(`/api/customers/${customerId}/machines`);
        const machines = machRes.data;
        
        const select = document.getElementById('detail-meter-machine');
        select.innerHTML = '<option value="">Cihaz Seçin...</option>';
        machines.forEach(m => {
            const opt = document.createElement('option');
            opt.value = m.id;
            opt.textContent = `${m.machineName} (${m.serialNumber})`;
            opt.setAttribute('data-current', m.currentMeter);
            select.appendChild(opt);
        });

        // Update read-only label on change
        select.addEventListener('change', () => {
            const opt = select.options[select.selectedIndex];
            const infoLabel = document.getElementById('detail-meter-info');
            if (!opt || !opt.value) {
                infoLabel.textContent = 'Model: - | Güncel Sayaç: -';
                return;
            }
            infoLabel.innerHTML = `Model: <strong>${opt.text}</strong> | Önceki Son Sayaç: <strong>${opt.getAttribute('data-current')}</strong>`;
        });

        // Load reading log table
        const tbody = document.getElementById('tbody-detail-readings');
        tbody.innerHTML = '';
        
        // Sum readings from all machines for this customer
        for (const m of machines) {
            const readRes = await apiCall(`/api/customer-machines/${m.id}/meter-readings`);
            const readings = readRes.data;
            
            readings.forEach(r => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${formatDateString(r.readingDate)}</td>
                    <td><strong>${r.meterValue}</strong></td>
                    <td>${r.previousMeterValue}</td>
                    <td class="text-success">+${r.difference}</td>
                    <td>${r.unitPrice.toFixed(4)} TL</td>
                    <td><strong>${r.totalAmount.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</strong></td>
                `;
                tbody.appendChild(tr);
            });
        }
    } catch (err) {
        console.error(err);
    }
}

// Subtab: Toner Deliveries
async function loadTonerDeliverySubtab(customerId) {
    try {
        // Load Toner Types dropdown
        const tRes = await apiCall('/api/toner-types');
        const types = tRes.data;
        
        const select = document.getElementById('detail-toner-type');
        select.innerHTML = '<option value="">Toner Hammaddesi Seçin...</option>';
        types.forEach(t => {
            const opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.tonerName;
            opt.setAttribute('data-costg', t.costPerGram);
            opt.setAttribute('data-pageg', t.pagesPerGram);
            select.appendChild(opt);
        });

        // Load deliveries table
        const res = await apiCall(`/api/customers/${customerId}/toners`);
        const deliveries = res.data;

        const tbody = document.getElementById('tbody-detail-toner');
        tbody.innerHTML = '';
        
        deliveries.forEach(d => {
            const tr = document.createElement('tr');
            
            let statusClass = 'text-success';
            if (d.status.includes('Tükendi') || d.status.includes('Aşıldı')) {
                statusClass = 'text-danger';
            }

            tr.innerHTML = `
                <td>${formatDateString(d.date)}</td>
                <td>${d.tonerName}</td>
                <td>${d.grams} gr</td>
                <td>${d.expectedPages.toLocaleString('tr-TR')}</td>
                <td>${d.actualCopies.toLocaleString('tr-TR')}</td>
                <td>${d.remaining.toLocaleString('tr-TR')}</td>
                <td>${d.cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</td>
                <td class="${statusClass}"><strong>${d.status}</strong></td>
                <td>
                    <button class="btn btn-danger op-row-action" onclick="deleteTonerDelivery(${d.id})">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

function recalculateTonerDetails() {
    const select = document.getElementById('detail-toner-type');
    const opt = select.options[select.selectedIndex];
    const grams = parseInt(document.getElementById('detail-toner-grams').value) || 0;
    const label = document.getElementById('detail-toner-calc-info');

    if (!opt || !opt.value || grams <= 0) {
        label.textContent = 'Tahmini Kapasite: - | Maliyet: -';
        return;
    }

    const costPerGram = parseFloat(opt.getAttribute('data-costg'));
    const cost = costPerGram * grams;
    // Standard rule: 1g = 20 pages expected
    const capacity = grams * 20;

    label.innerHTML = `Tahmini Kapasite: <strong>${capacity.toLocaleString('tr-TR')} sayfa</strong> | Hesaplanan Maliyet: <strong>${cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</strong>`;
}

async function deleteTonerDelivery(id) {
    if (confirm('Seçili toner teslimat kaydını silmek istediğinize emin misiniz?')) {
        try {
            const res = await apiCall(`/api/customers/toners/${id}`, 'DELETE');
            alert(res.message);
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    }
}

// Subtab: Services
async function loadServicesSubtab(customerId) {
    try {
        const res = await apiCall(`/api/customers/${customerId}/services`);
        const services = res.data;

        const tbody = document.getElementById('tbody-detail-services');
        tbody.innerHTML = '';
        
        services.forEach(s => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${formatDateString(s.date)}</td>
                <td>${s.desc}</td>
                <td><strong>${s.amount.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</strong></td>
                <td>
                    <button class="btn btn-danger op-row-action" onclick="deleteServiceExpense(${s.id})">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

async function deleteServiceExpense(id) {
    if (confirm('Seçili servis giderini silmek istediğinize emin misiniz?')) {
        try {
            const res = await apiCall(`/api/customers/services/${id}`, 'DELETE');
            alert(res.message);
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    }
}

// Subtab: Material Dispatches
async function loadMaterialDispatchesSubtab(customerId) {
    try {
        // Load machines dropdown in the form
        const machRes = await apiCall(`/api/customers/${customerId}/machines`);
        const machines = machRes.data;
        
        const selectMach = document.getElementById('detail-dispatch-machine');
        selectMach.innerHTML = '<option value="">Cihaz Seçin...</option>';
        machines.forEach(m => {
            const opt = document.createElement('option');
            opt.value = m.id;
            opt.textContent = `${m.machineName} (${m.serialNumber})`;
            selectMach.appendChild(opt);
        });

        // Load dispatches table
        const res = await apiCall(`/api/customers/${customerId}/dispatches`);
        const dispatches = res.data;

        const tbody = document.getElementById('tbody-detail-dispatches');
        tbody.innerHTML = '';
        
        dispatches.forEach(d => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${formatDateString(d.date)}</td>
                <td>${d.machine}</td>
                <td><strong>${d.productName}</strong></td>
                <td>${d.quantity} Adet</td>
                <td>${d.desc}</td>
                <td>${d.billed > 0 ? d.billed.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' : '-'}</td>
                <td>${d.cost > 0 ? d.cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' : '-'}</td>
                <td>
                    <button class="btn btn-danger op-row-action" onclick="deleteDispatch(${d.id})">İptal Et</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

// Helper to load products inside detail dispatch form based on selected source (Warehouse vs Employee Zimmet)
async function populateDetailDispatchProductsDropdown() {
    const isWarehouse = document.querySelector('input[name="dispatch-source"]:checked').value === 'warehouse';
    const select = document.getElementById('detail-dispatch-prod');
    select.innerHTML = '<option value="">Seçiniz...</option>';

    if (isWarehouse) {
        if (window.allProducts) {
            window.allProducts.forEach(p => {
                if (p.warehouseQuantity > 0) {
                    const opt = document.createElement('option');
                    opt.value = p.id;
                    opt.textContent = `${p.name} (${p.stockCode}) - Depo: ${p.warehouseQuantity}`;
                    opt.setAttribute('data-cost', 0);
                    select.appendChild(opt);
                }
            });
        }
    } else {
        const empId = document.getElementById('detail-dispatch-emp').value;
        if (!empId) return;
        
        try {
            const res = await apiCall(`/api/employees/${empId}/handovers`);
            const handovers = res.data;
            handovers.forEach(h => {
                const opt = document.createElement('option');
                opt.value = h.productId;
                opt.textContent = `${h.productName} (${h.stockCode}) - Zimmet: ${h.quantity}`;
                opt.setAttribute('data-cost', 0);
                select.appendChild(opt);
            });
        } catch (err) {
            console.error(err);
        }
    }
}

async function deleteDispatch(id) {
    if (confirm('Seçili malzeme çıkış kaydını iptal etmek istiyor musunuz?\nUYARI: Stoklar otomatik geri yüklenecektir!')) {
        try {
            const res = await apiCall(`/api/customers/dispatches/${id}`, 'DELETE');
            alert(res.message);
            loadCustomerDetailModal(activeCustomerId);
        } catch (err) {
            alert(err.message);
        }
    }
}

// ----------------------------------------------------
// MACHINES INFO TAB (ADMIN ONLY)
// ----------------------------------------------------
async function loadMachinesData() {
    try {
        const res = await apiCall('/api/customer-machines');
        const machines = res.data;
        window.allMachines = machines;

        const tbody = document.getElementById('tbody-machines');
        tbody.innerHTML = '';

        machines.forEach(m => {
            const tr = document.createElement('tr');
            
            let ownDisp = m.ownershipType === 'MÜŞTERİNİN_MAKİNESİ' ? 'Müşterinin' : 'Bizim';

            tr.innerHTML = `
                <td><strong>${m.companyName}</strong></td>
                <td>${m.machineName}</td>
                <td><code>${m.serialNumber}</code></td>
                <td>${m.installationDate}</td>
                <td>${ownDisp}</td>
                <td>${m.initialMeter}</td>
                <td><strong>${m.currentMeter}</strong></td>
                <td class="text-success">${m.totalCopies.toLocaleString('tr-TR')}</td>
                <td><strong>${m.totalRevenue.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</strong></td>
                <td>
                    <button class="btn btn-secondary op-row-action" onclick="openEditMachineModal(${m.id})">Düzenle</button>
                    <button class="btn btn-danger op-row-action" onclick="deleteMachine(${m.id}, '${m.machineName}')">Sil</button>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

function openEditMachineModal(id) {
    const m = window.allMachines.find(x => x.id === id);
    if (!m) return;

    document.getElementById('edit-mach-id').value = m.id;
    document.getElementById('edit-mach-cust').value = m.customerId;
    document.getElementById('edit-mach-name').value = m.machineName;
    document.getElementById('edit-mach-serial').value = m.serialNumber;
    document.getElementById('edit-mach-initial-meter').value = m.initialMeter;
    document.getElementById('edit-mach-current-meter').value = m.currentMeter;
    document.getElementById('edit-mach-initial-date').value = m.initialMeterDate;
    document.getElementById('edit-mach-install-date').value = m.installationDate;
    document.getElementById('edit-mach-ownership').value = m.ownershipType;
    
    openModal('modal-edit-machine');
}

async function deleteMachine(id, name) {
    if (confirm(`"${name}" cihazını sistemden silmek istediğinize emin misiniz?\nUYARI: Cihaza bağlı tüm sayaç okumaları ve operasyon logları silinecektir!`)) {
        try {
            const res = await apiCall(`/api/customer-machines/${id}`, 'DELETE');
            alert(res.message);
            loadMachinesData();
        } catch (err) {
            alert(err.message);
        }
    }
}

// ----------------------------------------------------
// CONTRACTS & OPERASYON PANEL (ADMIN ONLY)
// ----------------------------------------------------
async function loadContractsData() {
    try {
        const res = await apiCall('/api/contracts/reporting');
        const data = res.data;

        // Display stats cards
        document.getElementById('report-kb-toner-cost').textContent = data.kbTonerCost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';
        document.getElementById('report-mk-toner-cost').textContent = data.mkTonerCost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';
        document.getElementById('report-mk-material-cost').textContent = data.mkMaterialCost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';
        document.getElementById('report-kb-material-cost').textContent = data.kbMaterialCost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';
        document.getElementById('report-sozlesmesiz-revenue').textContent = data.sozlesmesizRevenue.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';

        // Display distributions table
        const tbodyDist = document.getElementById('tbody-operations-history').parentNode.parentNode.querySelector('#tbody-operations-history').parentNode.parentNode.parentNode.querySelector('table tbody');
        tbodyDist.innerHTML = '';
        data.distributions.forEach(d => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong>${d.businessModel}</strong></td>
                <td>${d.ownershipType}</td>
                <td><span class="badge">${d.count} Adet</span></td>
            `;
            tbodyDist.appendChild(tr);
        });

        // Display operations history log
        const tbodyHist = document.getElementById('tbody-operations-history');
        tbodyHist.innerHTML = '';
        data.operationsLog.forEach(h => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${formatDateString(h.date)}</td>
                <td>${h.machine}</td>
                <td><strong>${h.operationType}</strong></td>
                <td>${h.desc}</td>
                <td>${h.billed > 0 ? h.billed.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' : '-'}</td>
                <td>${h.cost > 0 ? h.cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL' : '-'}</td>
                <td>${h.expectedPages}</td>
            `;
            tbodyHist.appendChild(tr);
        });

        // Load contract device list in Operation panel dropdown
        const devRes = await apiCall('/api/contracts/devices');
        const devices = devRes.data;

        // Note: devices list is loaded, but wait!
        // The operations log points to customer_machines!
        // To keep database matching, we populate select-op-device from customer_machines!
        const machRes = await apiCall('/api/customer-machines');
        const machines = machRes.data;

        const selectOpDevice = document.getElementById('select-op-device');
        selectOpDevice.innerHTML = '<option value="">İşlem Yapılacak Cihaz Seçin...</option>';
        machines.forEach(m => {
            // Business model display
            const cust = window.allCustomers?.find(x => x.id === m.customerId);
            const bm = cust ? cust.businessModel : 'NORMAL';
            const bmDisp = bm === 'KOPYA_BASI' ? 'Kopya Başı' : (bm === 'MALZEME_KARSILIGI' ? 'Malzeme Karşılığı' : 'Normal');

            const opt = document.createElement('option');
            opt.value = m.id;
            opt.textContent = `${m.machineName} (${m.serialNumber}) - ${m.companyName}`;
            opt.setAttribute('data-company', m.companyName);
            opt.setAttribute('data-custid', m.customerId);
            opt.setAttribute('data-bm', bmDisp);
            opt.setAttribute('data-own', m.ownershipType === 'MÜŞTERİNİN_MAKİNESİ' ? 'Müşterinin' : 'Bizim');
            selectOpDevice.appendChild(opt);
        });

        // Load Toner list in operations panel
        const tonerRes = await apiCall('/api/toner-types');
        const toners = tonerRes.data;
        const selectOpToner = document.getElementById('select-op-toner');
        selectOpToner.innerHTML = '<option value="">Seçiniz...</option>';
        toners.forEach(t => {
            const opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.tonerName;
            selectOpToner.appendChild(opt);
        });

    } catch (err) {
        console.error(err);
    }
}

// Populate product selections inside the Contract operations form based on selected source (Warehouse vs Employee Zimmet)
async function populateContractSparePartsDropdown() {
    const isWarehouse = document.getElementById('select-op-source').value === 'warehouse';
    const select = document.getElementById('select-op-prod');
    select.innerHTML = '<option value="">Seçiniz...</option>';

    if (isWarehouse) {
        if (window.allProducts) {
            window.allProducts.forEach(p => {
                if (p.warehouseQuantity > 0) {
                    const opt = document.createElement('option');
                    opt.value = p.id;
                    opt.textContent = `${p.name} (${p.stockCode}) - Depo: ${p.warehouseQuantity}`;
                    opt.setAttribute('data-cost', 0);
                    select.appendChild(opt);
                }
            });
        }
    } else {
        const empId = document.getElementById('select-op-emp').value;
        if (!empId) return;
        
        try {
            const res = await apiCall(`/api/employees/${empId}/handovers`);
            const handovers = res.data;
            handovers.forEach(h => {
                const opt = document.createElement('option');
                opt.value = h.productId;
                opt.textContent = `${h.productName} (${h.stockCode}) - Zimmet: ${h.quantity}`;
                opt.setAttribute('data-cost', 0);
                select.appendChild(opt);
            });
        } catch (err) {
            console.error(err);
        }
    }
}

// ----------------------------------------------------
// TONER ANALYSIS (ADMIN ONLY)
// ----------------------------------------------------
async function loadTonerAnalysisData() {
    try {
        // Load Toner Types
        const tRes = await apiCall('/api/toner-types');
        const types = tRes.data;

        // Populate Calculator Dropdown
        const selectCalc = document.getElementById('select-calc-toner');
        selectCalc.innerHTML = '<option value="">Toner Hammaddesi Seçin...</option>';
        
        const tbodyTypes = document.getElementById('tbody-toner-types');
        tbodyTypes.innerHTML = '';

        types.forEach(t => {
            // Populate Calculator Dropdown
            const opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.tonerName;
            opt.setAttribute('data-costg', t.costPerGram);
            opt.setAttribute('data-pageg', t.pagesPerGram);
            selectCalc.appendChild(opt);

            // Populate table
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><strong>${t.tonerName}</strong></td>
                <td>${t.unitKgCost.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</td>
                <td>${t.standardPageCapacity.toLocaleString('tr-TR')} Sayfa</td>
                <td>
                    <button class="btn btn-secondary op-row-action" onclick="openEditTonerTypeModal(${t.id}, '${t.tonerName}', ${t.unitKgCost}, ${t.standardPageCapacity})">Düzenle</button>
                    <button class="btn btn-danger op-row-action" onclick="deleteTonerType(${t.id}, '${t.tonerName}')">Sil</button>
                </td>
            `;
            tbodyTypes.appendChild(tr);
        });

        // Load Fillings History
        const fRes = await apiCall('/api/toner-fillings');
        const fillings = fRes.data;

        const tbodyFillings = document.getElementById('tbody-toner-fillings');
        tbodyFillings.innerHTML = '';

        fillings.forEach(f => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${formatDateString(f.date)}</td>
                <td><strong>${f.tonerName}</strong></td>
                <td>${f.grams} gr</td>
                <td>${f.cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</td>
                <td>${f.expectedPages.toLocaleString('tr-TR')} sayfa</td>
                <td>
                    <button class="btn btn-danger op-row-action" onclick="deleteTonerFilling(${f.id})">Sil</button>
                </td>
            `;
            tbodyFillings.appendChild(tr);
        });

    } catch (err) {
        console.error(err);
    }
}

function openEditTonerTypeModal(id, name, cost, capacity) {
    document.getElementById('edit-toner-id').value = id;
    document.getElementById('edit-toner-name').value = name;
    document.getElementById('edit-toner-cost-kg').value = cost;
    document.getElementById('edit-toner-capacity').value = capacity;
    openModal('modal-edit-toner-type');
}

async function deleteTonerType(id, name) {
    if (confirm(`"${name}" toner türünü ve tüm dolum geçmişini silmek istediğinize emin misiniz?`)) {
        try {
            const res = await apiCall(`/api/toner-types/${id}`, 'DELETE');
            alert(res.message);
            loadTonerAnalysisData();
        } catch (err) {
            alert(err.message);
        }
    }
}

async function deleteTonerFilling(id) {
    if (confirm('Bu dolum kaydını silmek istediğinize emin misiniz?')) {
        try {
            const res = await apiCall(`/api/toner-fillings/${id}`, 'DELETE');
            alert(res.message);
            loadTonerAnalysisData();
        } catch (err) {
            alert(err.message);
        }
    }
}

function recalculateTonerCalcResult() {
    const select = document.getElementById('select-calc-toner');
    const opt = select.options[select.selectedIndex];
    const presetVal = document.getElementById('select-calc-preset').value;
    
    let grams = 0;
    if (presetVal === 'custom') {
        grams = parseInt(document.getElementById('calc-custom-grams').value) || 0;
    } else {
        grams = parseInt(presetVal) || 0;
    }

    const labelCost = document.getElementById('calc-result-cost');
    const labelPages = document.getElementById('calc-result-pages');

    if (!opt || !opt.value || grams <= 0) {
        labelCost.textContent = '0.00 TL';
        labelPages.textContent = '0 Sayfa';
        return;
    }

    const costPerGram = parseFloat(opt.getAttribute('data-costg'));
    const pagesPerGram = parseFloat(opt.getAttribute('data-pageg'));

    const cost = costPerGram * grams;
    const pages = pagesPerGram * grams;

    labelCost.textContent = cost.toLocaleString('tr-TR', { minimumFractionDigits: 2 }) + ' TL';
    labelPages.textContent = Math.round(pages).toLocaleString('tr-TR') + ' Sayfa';
}

// ----------------------------------------------------
// STOCK MOVEMENTS HISTORY TAB (ADMIN ONLY)
// ----------------------------------------------------
async function loadStockHistoryData() {
    try {
        const start = document.getElementById('filter-history-start').value;
        const end = document.getElementById('filter-history-end').value;
        
        let url = '/api/stock-movements';
        if (start || end) {
            url += `?start=${start}&end=${end}`;
        }

        const res = await apiCall(url);
        const movements = res.data;

        const tbody = document.getElementById('tbody-stock-history');
        tbody.innerHTML = '';

        movements.forEach(m => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${formatDateString(m.transactionDate)}</td>
                <td><strong>${m.transactionType}</strong></td>
                <td><code>${m.stockCode}</code></td>
                <td>${m.productName}</td>
                <td>${m.employeeName}</td>
                <td>${m.customerName}</td>
                <td>${m.quantity} Adet</td>
                <td>${m.description}</td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

// ----------------------------------------------------
// YEARLY COPY READING REPORT (ADMIN ONLY)
// ----------------------------------------------------
async function loadYearlyReportData() {
    try {
        const year = document.getElementById('select-report-year').value;
        const res = await apiCall(`/api/reports/yearly?year=${year}`);
        const rows = res.data;

        const tbody = document.getElementById('tbody-yearly-report');
        tbody.innerHTML = '';

        rows.forEach(r => {
            const tr = document.createElement('tr');
            
            // Build monthly copy values columns
            let monthsHtml = '';
            for (let m = 1; m <= 12; m++) {
                const val = r.monthlyCopies[String(m)] || 0;
                monthsHtml += `<td align="right">${val > 0 ? val.toLocaleString('tr-TR') : '-'}</td>`;
            }

            tr.innerHTML = `
                <td>${r.companyName}</td>
                <td>${r.responsibleEmployee}</td>
                ${monthsHtml}
                <td align="right" class="text-success"><strong>${r.yearlyCopiesTotal.toLocaleString('tr-TR')}</strong></td>
                <td align="right"><strong>${r.yearlyRevenueTotal.toLocaleString('tr-TR', { minimumFractionDigits: 2 })} TL</strong></td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        console.error(err);
    }
}

// ----------------------------------------------------
// UI HELPERS (SEARCH, DATE SETTINGS, MODALS)
// ----------------------------------------------------
function setupSearchFilters() {
    // Products search
    document.getElementById('search-products').addEventListener('input', (e) => {
        const text = e.target.value.toLowerCase();
        const rows = document.querySelectorAll('#tbody-products tr');
        rows.forEach(row => {
            const content = row.textContent.toLowerCase();
            if (content.includes(text)) row.style.display = '';
            else row.style.display = 'none';
        });
    });

    // Handover employee cards search
    document.getElementById('search-handover-employees').addEventListener('input', (e) => {
        const text = e.target.value.toLowerCase();
        const cards = document.querySelectorAll('#handover-cards-grid .handover-card');
        cards.forEach(card => {
            const name = card.getAttribute('data-name');
            if (name.includes(text)) card.style.display = '';
            else card.style.display = 'none';
        });
    });

    // Customers search and filter combo
    const filterCust = () => {
        const search = document.getElementById('search-customers').value.toLowerCase();
        const resp = document.getElementById('filter-customer-responsible').value;
        const model = document.getElementById('filter-customer-business').value;
        
        const rows = document.querySelectorAll('#tbody-customers tr');
        rows.forEach(row => {
            const cells = row.getElementsByTagName('td');
            if (cells.length === 0) return;
            
            const code = cells[0].textContent.toLowerCase();
            const company = cells[1].textContent.toLowerCase();
            
            // Filters
            const cust = window.allCustomers.find(x => x.id === parseInt(row.getAttribute('data-id')));
            if (!cust) return;

            const matchesSearch = code.includes(search) || company.includes(search);
            const matchesResp = !resp || cust.responsibleId === parseInt(resp);
            const matchesModel = !model || cust.businessModel === model;

            if (matchesSearch && matchesResp && matchesModel) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        });
    };

    document.getElementById('search-customers').addEventListener('input', filterCust);
    document.getElementById('filter-customer-responsible').addEventListener('change', filterCust);
    document.getElementById('filter-customer-business').addEventListener('change', filterCust);

    // Machines search and filter
    const filterMach = () => {
        const search = document.getElementById('search-machines').value.toLowerCase();
        const own = document.getElementById('filter-machine-ownership').value;

        const rows = document.querySelectorAll('#tbody-machines tr');
        rows.forEach(row => {
            const content = row.textContent.toLowerCase();
            const mach = window.allMachines.find(x => x.serialNumber === row.getElementsByTagName('td')[2].textContent.trim().replace(/`/g, ''));
            if (!mach) return;

            const matchesSearch = content.includes(search);
            const matchesOwn = !own || mach.ownershipType === own;

            if (matchesSearch && matchesOwn) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        });
    };

    document.getElementById('search-machines').addEventListener('input', filterMach);
    document.getElementById('filter-machine-ownership').addEventListener('change', filterMach);

    // Stock History search
    document.getElementById('search-history').addEventListener('input', (e) => {
        const text = e.target.value.toLowerCase();
        const rows = document.querySelectorAll('#tbody-stock-history tr');
        rows.forEach(row => {
            const content = row.textContent.toLowerCase();
            if (content.includes(text)) row.style.display = '';
            else row.style.display = 'none';
        });
    });
    
    // Set default install dates for Machine form
    setInstallDatesToday();
}

function setInstallDatesToday() {
    const today = new Date().toISOString().split('T')[0];
    const initialDate = document.getElementById('mach-initial-date');
    const installDate = document.getElementById('mach-install-date');
    if (initialDate) initialDate.value = today;
    if (installDate) installDate.value = today;
}

// Modal open/close helpers
function openModal(modalId) {
    document.getElementById(modalId).classList.remove('hidden');
}

function closeModal(modalId) {
    document.getElementById(modalId).classList.add('hidden');
}

// Clean helper to parse timestamp strings
function formatDateString(timestampStr) {
    if (!timestampStr) return '-';
    // Format "yyyy-MM-dd HH:mm:ss" -> "dd.MM.yyyy"
    const parts = timestampStr.split(' ')[0].split('-');
    if (parts.length === 3) {
        return `${parts[2]}.${parts[1]}.${parts[0]}`;
    }
    return timestampStr;
}

// Client side CSV Excel Exporter
function exportTableToCSV(filename, tableId) {
    const csv = [];
    const rows = document.querySelectorAll(`#${tableId} tr`);
    
    for (let i = 0; i < rows.length; i++) {
        // Skip hidden rows
        if (rows[i].style.display === 'none') continue;
        
        const row = [];
        const cols = rows[i].querySelectorAll('td, th');
        
        for (let j = 0; j < cols.length; j++) {
            // Skip action columns (if contains buttons)
            if (cols[j].querySelector('button')) continue;
            
            // Clean value, escape double quotes
            let val = cols[j].textContent.trim();
            val = val.replace(/"/g, '""');
            row.push(`"${val}"`);
        }
        
        csv.push(row.join(';')); // semicolon separated for Turkish Excel default parsing
    }

    // Download CSV
    const csvContent = '\uFEFF' + csv.join('\n'); // Add UTF-8 BOM
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', filename);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}

// ======================================================
// PDF Report Wizard Functions
// ======================================================
async function openPdfWizard() {
    try {
        const res = await apiCall('/api/employees');
        const employees = res.data;
        
        const select = document.getElementById('select-pdf-employee');
        select.innerHTML = '<option value="Tümü">Tüm Personeller</option>';
        employees.forEach(e => {
            const opt = document.createElement('option');
            opt.value = e.id;
            opt.textContent = `${e.firstName} ${e.lastName}`;
            select.appendChild(opt);
        });
    } catch (err) {
        console.error('Personel listesi yuklenemedi:', err);
    }

    // Default checklist items
    document.getElementById('chk-pdf-overview').checked = true;
    document.getElementById('chk-pdf-handovers').checked = true;
    document.getElementById('chk-pdf-machines').checked = true;

    // Open modal
    openModal('modal-pdf-wizard');
}

function initPdfWizard() {
    // Overview button click listener
    const btnOverview = document.getElementById('btn-open-pdf-wizard-overview');
    if (btnOverview) {
        btnOverview.addEventListener('click', openPdfWizard);
    }

    // Yearly report button click listener
    const btnYearly = document.getElementById('btn-open-pdf-wizard-yearly');
    if (btnYearly) {
        btnYearly.addEventListener('click', openPdfWizard);
    }

    // Modal PDF generation execute button
    const btnGenerate = document.getElementById('btn-generate-pdf-wizard');
    if (btnGenerate) {
        btnGenerate.addEventListener('click', async () => {
            const chkOverview = document.getElementById('chk-pdf-overview').checked;
            const chkHandovers = document.getElementById('chk-pdf-handovers').checked;
            const chkMachines = document.getElementById('chk-pdf-machines').checked;

            if (!chkOverview && !chkHandovers && !chkMachines) {
                alert('Lutfen en az bir sayfa secenegini isaretleyin!');
                return;
            }

            const empId = document.getElementById('select-pdf-employee').value;
            const year = parseInt(document.getElementById('select-pdf-year').value) || 2026;

            closeModal('modal-pdf-wizard');
            await runExportPDF(chkOverview, chkHandovers, chkMachines, empId, year);
        });
    }
}

async function runExportPDF(includeOverview, includeHandovers, includeMachines, employeeId, year) {
    try {
        const payload = {
            year: year,
            employeeId: employeeId,
            includeOverview: includeOverview,
            includeHandovers: includeHandovers,
            includeMachines: includeMachines
        };

        const response = await fetch('/api/reports/export-pdf', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (response.ok) {
            const blob = await response.blob();
            const downloadUrl = window.URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = downloadUrl;
            a.download = `Bormak_Performans_Raporu_${year}.pdf`;
            document.body.appendChild(a);
            a.click();
            a.remove();
        } else {
            const text = await response.text();
            alert('PDF raporu olusturulurken hata olustu: ' + text);
        }
    } catch (err) {
        console.error(err);
        alert('PDF olusturulurken baglanti hatasi olustu.');
    }
}
