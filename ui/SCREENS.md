# Mokobara RFID — Screen Guide

Prototype UI ka complete screen reference.  
Open hub: [`index.html`](index.html) → **C72 Handheld App** ya **Web Admin Portal**.

> Abhi ye **static HTML/CSS mockups** hain — real barcode/RFID scan, backend, Android SDK nahi.

**Core identity chain:** Factory `USN` (already → SKU in Unicommerce) · Map links `USN ↔ RFID` · `EAN → SKU` remains product master

---

## Quick map

| Surface | Path | Audience |
|---------|------|----------|
| Hub | `index.html` | Entry |
| C72 App | `app/*.html` | Warehouse / store operators |
| Admin | `admin/*.html` | Admin / supervisor |

### End-to-end warehouse story

```
Login → Home
  → Inward (GRN)
  → Map / Bulk map (USN ↔ RFID link)
  → Putaway (bin)
  → Search / Locate / Count (ops)
  → Pick → Transfer (WH → Store)
  → De-link (fix wrong mapping)
```

Admin parallel: Products · Locations · Users · Devices · Count approvals · Integration logs.

---

# Part A — Hub

## `index.html` — Prototype hub

**Kya hai:** Landing page — App vs Admin choose.

**Kaam:**
- C72 Handheld App open
- Web Admin Portal open

**Note:** Sample data — BAG001, SET3-1042, WH-01.

---

# Part B — C72 Handheld App (`ui/app/`)

## 1. `login.html` — Login

**Kaam:** User + device + site session start.

**Screen pe:**
- User ID / Password
- Device (auto): `C72-05 · SN-…`
- Site select: WH-01 / ST-05

**Flow:**
1. Credentials
2. Session bind to device ID
3. Active site choose
4. Har scan/audit pe user + device + site log

**Rules:** Lost device remote block; blocked device sync nahi kar sakta.

---

## 2. `home.html` — Home

**Kaam:** Operator ka main menu (site context ke saath).

**Tiles:**

| Group | Screens |
|-------|---------|
| Tag & identity | Map, Bulk map, De-link, Search |
| Find & count | Locate, Stock count |
| Warehouse | Inward, Putaway, Picking, Transfer |

Bottom nav: Home · Map · Search · Count · More.

---

## 3. `map.html` — Map RFID (single)

**Kaam:** Factory USN ko physical RFID se link karna. SKU Unicommerce se aata hai.

**Steps:** `1 USN` → `2 RFID` → `3 Save`

**Flow:**
1. USN barcode scan (factory label — pehle se generated)
2. Backend → Unicommerce API → product card (SKU, colour, pack, EAN)
3. RFID trigger scan
4. USN already linked ya RFID already mapped? → **block**
5. Free? → Save `USN ↔ RFID` (+ SKU snapshot) + location + audit

**Sets:** Pack type set hai to 2/3 child tags complete hone ke baad hi Save.

**USN:** App allocate nahi karta — factory / Unicommerce se aata hai, pehle se SKU se linked.

---

## 4. `bulk-map.html` — Bulk map

**Kaam:** Carton / tray pe kai units — har unit ke liye `USN → RFID` pair, phir ek save.

**Screen pe:**
- Session product (last USN ka SKU) + Carton ID
- Paired / Expected / Pending
- Pair list (USN ↔ EPC; tick/untick; already-mapped skip)
- **Save N pairs**

**Flow:**
1. Har unit: USN barcode → backend SKU fetch
2. Us unit ka RFID (low power — stray kam)
3. List me `USN ↔ RFID` pair add
4. Already-mapped USN/RFID skip; bad pairs untick
5. Ek save → N rows

**Also:** Vendor pre-tagged stock → admin CSV upload.

---

## 5. `delink.html` — De-link RFID

**Kaam:** Galat / damaged mapping todna; tag free for re-map.

**Flow:**
1. RFID scan
2. Currently linked: SKU, USN, status
3. Reason: Wrong SKU / Damaged / Replaced / Vendor return
4. Confirm → history + tag free
5. Optional: De-link & re-map → Map screen

**Rules:**
- Daily de-link threshold → supervisor
- Sold / dispatched → supervisor
- Bulk de-link → batch approval

---

## 6. `search.html` — Search (Tag → Product)

**Kaam:** Haath mein RFID → product kaunsa?

**Input:** RFID only (single-tag mode)  
**Output:** Name, SKU, EAN, USN, RFID, location, status

**Andar:** `item_units` by EPC → join `products` + `locations`.

**Unmapped tag:** Unknown / not mapped.

**Sets:** Parent SKU + sibling children.

**Vs Locate:** Search = tag se product · Locate = SKU se units.

---

## 7. `locate.html` — Locate + Find

**Kaam:** SKU/EAN/USN se units kahan hain + physical Find.

**Flow:**
1. Type/scan SKU / EAN / USN
2. List: units at current site + bins
3. Warning agar last-seen purana (count se pehle)
4. **Find →** ek unit select
5. Find mode: RSSI bar + beep tez jab paas

**Sets:** Har child location; split-across-bins flag.

---

## 8. `count.html` — Stock count

**Kaam:** Area walk + bulk RFID vs expected stock.

**Screen pe:** Matched / Missing / Extra · live reads · variance list · incomplete set alert

**Flow:**
1. Supervisor task create (full / cycle / location)
2. Operator area scan (de-duped live)
3. Compare expected vs found
4. Submit → Admin count tasks (approval)
5. Approve → Unicommerce / POSx

---

## 9. `inward.html` — Inward (GRN)

**Kaam:** Vendor/PO ke against receive — short / excess.

**Steps:** `PO` → `Scan` → `Submit`

**Flow:**
1. PO open (Unicommerce) — expected USNs from factory
2. Scan received unit USNs (or already-mapped RFIDs) vs expected
3. Received / Short / Excess
4. Submit → UC sync → Putaway

**Note:** Inward = receive verify. `USN ↔ RFID` link **Map** pe hoti hai (unless vendor CSV pehle se mapped).

---

## 10. `putaway.html` — Putaway

**Kaam:** Mapped units ko bin pe rakhna → `location_id` update.

**Steps:** `RFID` → `Bin` → `Done`

**Flow:**
1. Product RFID(s) select
2. Bin barcode / location RFID scan
3. Confirm → har unit ki location save
4. Locate baad mein yahi “last known” use karta hai

---

## 11. `pick.html` — Picking

**Kaam:** Order / transfer lines ke against sahi SKU pick.

**Screen pe:** Order ID · Need SKU × qty · Picked / Required / Left

**Flow:**
1. Transfer/order open
2. Har RFID scan — expected SKU match
3. **Wrong SKU → BLOCK** (silent accept nahi)
4. Complete → Transfer dispatch

**Sync:** Submit pe Unicommerce session post.

---

## 12. `transfer.html` — Stock transfer

**Kaam:** Warehouse → Store movement track.

**Steps:** `Pick` → `Dispatch` → `Transit` → `Receive`

**Is mock pe:** WH-01 → ST-05 · status **IN TRANSIT**

| Stage | Matlab |
|-------|--------|
| Pick | Units order ke hisaab pick |
| Dispatch | WH gate scan → IN TRANSIT |
| Transit | Raste mein (na WH missing, na store stock) |
| Receive | Store scan → location = store |

**Sets on store receive:** Children → single SKUs (POSx sell).  
**Sale:** POSx bill → RFID SOLD (real-time / ≤2h).  
**Integrations:** UC + POSx update taaki count transit/sold ko missing na maane.

---

# Part C — Web Admin (`ui/admin/`)

## 13. `dashboard.html` — Dashboard

**Kaam:** Ops health snapshot.

**KPIs:** Counts pending · Sync errors · Devices online · Mapped tags  
**Panels:** Recent count tasks · Integration queue

Operators C72 pe; supervisors yahan approve.

---

## 14. `products.html` — Product master

**Kaam:** SKU / EAN / pack / components (Unicommerce sync).

**Fields:** SKU, EAN, Name, Colour, Pack (Single / Set 2 / Set 3), Components, Status

**Rules:**
- Inactive SKU pe naya map nahi
- Sets mein component single SKUs store (store split bina re-tag)
- Refresh from UC (daily + on-demand)

**C72 Map:** USN lookup → Unicommerce/backend; product master (EAN/SKU) yahi sync se.

---

## 15. `locations.html` — Location master

**Kaam:** Site → Area → Zone/Rack → Bin tree.

**Use:** Map, putaway, count, locate — sab isi hierarchy pe.

Synced from UC / POSx on change.

---

## 16. `users.html` — Users & roles

| Role | Access |
|------|--------|
| Operator | Scan, map, count, search |
| Supervisor | Count approve, sensitive de-links |
| Admin | Masters, devices, settings, reports |

Users ko sites assign; invite / activate / deactivate.

---

## 17. `devices.html` — C72 devices

**Kaam:** Handheld register, block, reader settings.

**Login:** per user + per device.  
**Lost device:** remote block → sync band.  
**Settings:** Reader power / RSSI cut-offs (admin, hard-coded nahi).

---

## 18. `count-tasks.html` — Count tasks & approvals

**Kaam:** Task create → operator scan → variance review → Approve.

**Task types:** Full · Cycle · Location  
**Variance:** Missing · Extra · Incomplete set

**Approve** → post Unicommerce / POSx.  
App Count **Submit** prototype mein yahan land karta hai.

---

## 19. `integration-logs.html` — Integration queue & logs

**Kaam:** Unicommerce + POSx sync health.

| System | Owns |
|--------|------|
| Unicommerce | Warehouse / orders SoR |
| POSx | Store sales SoR |
| RFID app | Tag-level data (USN↔RFID↔SKU) |

Sync real-time jab API allow; warna ≤2h. Failures queue + retry; ops yahan fix.

---

# Screen comparison cheat-sheet

| Screen | Start with | End result |
|--------|------------|------------|
| Map | USN + 1 RFID | 1 unit mapped (`USN ↔ RFID`) |
| Bulk map | N× (USN + RFID) | N pairs mapped |
| De-link | RFID | Tag free + history |
| Search | RFID | Product details |
| Locate | SKU/EAN/USN | Units by bin + Find |
| Count | Count task | Matched/missing/extra |
| Inward | PO | GRN short/excess |
| Putaway | RFID + bin | location_id |
| Pick | Order line | Correct SKUs picked |
| Transfer | TO | WH → store movement |

---

# Sample IDs used in prototype

| ID | Meaning |
|----|---------|
| BAG001 | Cabin Bag 20" (single) |
| SET3-1042 | Travel set of 3 |
| EAN 890123456 | BAG001 barcode |
| USN000123 | Sample factory unit serial (pre-linked to SKU in UC) |
| E280…3456 | Sample RFID EPC |
| WH-01 | Mumbai Warehouse |
| ST-05 | Bandra Store |
| A-03-02 | Rack A bin |
| TO-ST05-0192 | Transfer order |
| C72-05 | Handheld device |

---

# Related docs in chat (concepts)

- **EAN** = product type barcode (master / Unicommerce)  
- **SKU** = product code; USN pehle se SKU se linked (Unicommerce)  
- **RFID** = physical chip on the unit  
- **USN** = unit serial from factory (pre-generated; not allocated by RFID app)  
- Mapping on **Map / Bulk map** = link `USN ↔ RFID`; arrival alone does not create the link  

---

*Last updated for HTML/CSS UI prototype — no backend yet.*
