#!/usr/bin/env python3
"""Generate scripts/clog_items.json straight from the OSRS game cache.

Replaces the wiki-scraped clog item list (scripts/scrape_clog_items.py) with the
authoritative source: the collection log is defined in the game cache as a tree of
enums and structs, so we read it directly and never wait on wiki editors.

Pipeline
--------
1. Find the newest live OSRS cache on the OpenRS2 archive (archive.openrs2.org).
2. Fetch *only* the config groups we need (enum/obj/struct) — not the whole cache.
3. Walk the collection-log tree:
       enum 2102  -> 5 tab structs (Bosses, Raids, Clues, Minigames, Other)
       tab struct -> param 682 = tab name, param 683 = page-list enum
       page-list  -> ordered page struct ids
       page struct-> param 689 = page name, param 690 = item-id enum
       item enum  -> ordered item ids
   Item names come from the item definitions (obj, opcode 2).
4. Resolve each item's wiki page from its id via the wiki's Special:Lookup redirect,
   reusing the pages already in the previous clog_items.json (so existing anchors are
   preserved and we only hit the wiki for genuinely new ids), with an overrides file
   for manual corrections.
5. Emit clog_items.json: sorted by id, 2-space indent, keys id/name/tabs/wiki_page.

Stdlib only — no pip dependencies.

Usage:
    python scripts/gen_clog_items.py --out scripts/clog_items.json \
        --prev scripts/clog_items.json \
        --overrides scripts/clog_wiki_overrides.json
"""
import argparse, bz2, gzip, json, sys, time, urllib.parse, urllib.request

UA = "clog-items-generator (+https://github.com/pairofcrocs/drop-rates-clog)"
OPENRS2 = "https://archive.openrs2.org"
WIKI_LOOKUP = "https://oldschool.runescape.wiki/w/Special:Lookup"

# Config archive (index 2) and the groups within it we read.
CONFIG_ARCHIVE = 2
GROUP_ENUM, GROUP_OBJ, GROUP_STRUCT = 8, 10, 34
REF_TABLE_ARCHIVE = 255

# Collection-log tree ids/params (see module docstring).
TOP_ENUM = 2102
P_TAB_NAME, P_PAGES_ENUM = 682, 683
P_PAGE_NAME, P_ITEMS_ENUM = 689, 690


# ── byte reader ──────────────────────────────────────────────────────────────
class R:
    def __init__(self, b): self.b, self.i = b, 0
    def u8(self):  v = self.b[self.i]; self.i += 1; return v
    def u16(self): v = int.from_bytes(self.b[self.i:self.i+2], "big"); self.i += 2; return v
    def i32(self): v = int.from_bytes(self.b[self.i:self.i+4], "big", signed=True); self.i += 4; return v
    def u32(self): v = int.from_bytes(self.b[self.i:self.i+4], "big"); self.i += 4; return v
    def u24(self): v = int.from_bytes(self.b[self.i:self.i+3], "big"); self.i += 3; return v
    def i8(self):  v = self.u8(); return v - 256 if v > 127 else v
    def big_smart(self):
        return (self.u32() & 0x7fffffff) if (self.b[self.i] & 0x80) else self.u16()
    def jstr(self):
        end = self.b.index(0, self.i)
        s = self.b[self.i:end].decode("latin-1"); self.i = end + 1; return s
    def rem(self): return len(self.b) - self.i


# ── cache container / index / group ──────────────────────────────────────────
def decompress(data):
    r = R(data); ctype = r.u8(); clen = r.u32()
    if ctype == 0:
        return data[r.i:r.i+clen]
    r.u32()  # decompressed length
    payload = data[r.i:r.i+clen]
    if ctype == 1: return bz2.decompress(b"BZh1" + payload)
    if ctype == 2: return gzip.decompress(payload)
    if ctype == 3:
        import lzma; return lzma.decompress(payload)
    raise ValueError(f"unknown compression {ctype}")


def parse_index(raw):
    """Parse a JS5 reference table -> {group_id: [file_id, ...]}."""
    r = R(raw); protocol = r.u8()
    if not 5 <= protocol <= 7:
        raise ValueError(f"bad index protocol {protocol}")
    if protocol >= 6: r.i32()               # revision
    flags = r.u8()
    NAMES, DIGEST, LENGTHS, UNCRC = flags & 1, flags & 2, flags & 4, flags & 8
    rd = r.big_smart if protocol >= 7 else r.u16
    count = rd()
    gids, acc = [], 0
    for _ in range(count): acc += rd(); gids.append(acc)
    if NAMES:  [r.i32() for _ in range(count)]
    [r.i32() for _ in range(count)]          # crc
    if UNCRC:  [r.i32() for _ in range(count)]
    if DIGEST:
        for _ in range(count): r.i += 64
    if LENGTHS:
        for _ in range(count): r.i32(); r.i32()
    [r.i32() for _ in range(count)]          # version
    child_counts = [rd() for _ in range(count)]
    out = {}
    for gi, gid in enumerate(gids):
        acc = 0; ids = []
        for _ in range(child_counts[gi]): acc += rd(); ids.append(acc)
        out[gid] = ids
    return out


def unpack_group(raw, file_ids):
    n = len(file_ids)
    if n == 1:
        return {file_ids[0]: raw}
    num_chunks = raw[-1]
    r = R(raw); r.i = len(raw) - 1 - num_chunks * n * 4
    sizes = [[0] * n for _ in range(num_chunks)]
    for c in range(num_chunks):
        acc = 0
        for f in range(n): acc += r.i32(); sizes[c][f] = acc
    out = {fid: bytearray() for fid in file_ids}; off = 0
    for c in range(num_chunks):
        for f in range(n):
            sz = sizes[c][f]; out[file_ids[f]].extend(raw[off:off+sz]); off += sz
    return {fid: bytes(b) for fid, b in out.items()}


# ── definition parsers ───────────────────────────────────────────────────────
def parse_enum(b):
    r = R(b); m = {}
    while True:
        op = r.u8()
        if op == 0: break
        if op in (1, 2): r.u8()
        elif op == 3: r.jstr()
        elif op == 4: r.i32()
        elif op == 5:
            for _ in range(r.u16()): k = r.i32(); m[k] = r.jstr()
        elif op == 6:
            for _ in range(r.u16()): k = r.i32(); m[k] = r.i32()
        else: raise ValueError(f"enum opcode {op}")
    return m


def parse_struct(b):
    r = R(b); params = {}
    while True:
        op = r.u8()
        if op == 0: break
        if op == 249:
            for _ in range(r.u8()):
                is_str = r.u8() == 1; key = r.u24()
                params[key] = r.jstr() if is_str else r.i32()
        else: raise ValueError(f"struct opcode {op}")
    return params


def parse_item_name(b):
    """Full item-definition parse (RuneLite ItemLoader opcodes) -> name (opcode 2)."""
    r = R(b); name = None
    while True:
        op = r.u8()
        if op == 0: break
        if op == 2: name = r.jstr()
        elif op == 1 or op in (4, 5, 6, 7, 8): r.u16()
        elif op in (3, 9): r.jstr()
        elif op in (11, 15, 16, 65, 160): pass
        elif op == 12: r.i32()
        elif op in (13, 14, 27, 42, 113, 114): r.i8()
        elif op in (23, 25): r.u16(); r.u8()
        elif op in (24, 26): r.u16()
        elif 30 <= op < 40: r.jstr()
        elif op in (40, 41):
            for _ in range(r.u8()): r.u16(); r.u16()
        elif op == 43:
            r.u8()
            while (r.u8() - 1) != -1: r.jstr()
        elif op in (44, 46, 47, 49, 50, 51, 52, 53, 54): r.i32()
        elif op in (45, 48): r.i32(); r.u8()
        elif op in (75, 78, 79, 90, 91, 92, 93, 94, 95, 97, 98,
                    110, 111, 112, 139, 140, 148, 149): r.u16()
        elif 100 <= op < 110: r.u16(); r.u16()
        elif op == 115: r.u8()
        elif op == 200: r.u8(); r.u8(); r.jstr()
        elif op == 201: r.u8(); r.u16(); r.u16(); r.i32(); r.i32(); r.jstr()
        elif op == 202: r.u8(); r.u16(); r.u16(); r.u16(); r.i32(); r.i32(); r.jstr()
        elif op == 249:
            for _ in range(r.u8()):
                is_str = r.u8() == 1; r.u24()
                r.jstr() if is_str else r.i32()
        else: raise ValueError(f"item opcode {op}")
    return name


# ── HTTP ─────────────────────────────────────────────────────────────────────
def http(url, method="GET"):
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent", UA)
    return urllib.request.urlopen(req, timeout=60)


def latest_cache_id():
    """Newest live, English OSRS cache from the OpenRS2 archive."""
    with http(OPENRS2 + "/caches.json") as resp:
        caches = json.loads(resp.read())
    def ok(c):
        return (c.get("game") == "oldschool" and c.get("scope") == "runescape"
                and c.get("environment") == "live" and c.get("builds")
                and (not c.get("languages") or "en" in c["languages"]))
    live = [c for c in caches if ok(c)]
    if not live:
        raise SystemExit("no suitable OSRS cache found on OpenRS2")
    live.sort(key=lambda c: c.get("timestamp") or "", reverse=True)
    best = live[0]
    build = best["builds"][0]
    return best["id"], f'{build.get("major")}.{build.get("minor") or 0}', best.get("timestamp")


def fetch_group(cache_id, archive, group):
    with http(f"{OPENRS2}/caches/runescape/{cache_id}/archives/{archive}/groups/{group}.dat") as resp:
        return resp.read()


# ── wiki page resolution ─────────────────────────────────────────────────────
def resolve_wiki_page(item_id, tries=3):
    """item id -> wiki page title (with any #anchor), via Special:Lookup's redirect.
    Returns None if the wiki has no mapping for the id (it redirects to the site
    root then). Retries transient network errors so a blip doesn't read as a miss."""
    url = f"{WIKI_LOOKUP}?type=item&id={item_id}"
    final = None
    for attempt in range(tries):
        try:
            resp = http(url, method="HEAD")
            final = resp.geturl()
            break
        except urllib.error.HTTPError as e:
            final = e.headers.get("Location") if e.code in (301, 302) else None
            break
        except urllib.error.URLError:
            if attempt == tries - 1:
                raise            # network problem, not a missing page — caller decides
            time.sleep(2 * (attempt + 1))
    prefix = "/w/"
    if not final or prefix not in final:
        return None
    title = final.split(prefix, 1)[1].split("?", 1)[0]   # defensively drop any query
    title = urllib.parse.unquote(title).replace("_", " ")
    if not title or title.startswith("Special:"):        # search page etc., not an article
        return None
    return title


# ── main ─────────────────────────────────────────────────────────────────────
def build_items(cache_id):
    index = parse_index(decompress(fetch_group(cache_id, REF_TABLE_ARCHIVE, CONFIG_ARCHIVE)))
    def load(group):
        return unpack_group(decompress(fetch_group(cache_id, CONFIG_ARCHIVE, group)), index[group])
    enums = {fid: parse_enum(b) for fid, b in load(GROUP_ENUM).items()}
    structs = {fid: parse_struct(b) for fid, b in load(GROUP_STRUCT).items()}
    names = {}
    for fid, b in load(GROUP_OBJ).items():
        nm = parse_item_name(b)
        if nm: names[fid] = nm

    items = {}  # id -> {"name", "tabs": [page, ...]}
    for tidx in sorted(enums[TOP_ENUM]):
        tab = structs[enums[TOP_ENUM][tidx]]
        pages = enums[tab[P_PAGES_ENUM]]
        for pidx in sorted(pages):
            page = structs[pages[pidx]]
            pname = page[P_PAGE_NAME]
            item_enum = enums[page[P_ITEMS_ENUM]]
            for iidx in sorted(item_enum):
                iid = item_enum[iidx]
                rec = items.setdefault(iid, {"name": names.get(iid, f"item_{iid}"), "tabs": []})
                if pname not in rec["tabs"]:
                    rec["tabs"].append(pname)
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--prev", default="", help="previous clog_items.json to reuse wiki_page from")
    ap.add_argument("--overrides", default="", help="JSON {id: wiki_page} manual overrides")
    ap.add_argument("--cache-id", type=int, default=0, help="force an OpenRS2 cache id")
    ap.add_argument("--sleep", type=float, default=0.3, help="delay between wiki lookups (s)")
    ap.add_argument("--strict", action="store_true",
                    help="exit 1 instead of writing output if any wiki lookup misses "
                         "(for CI: a name-fallback wiki_page would otherwise be reused "
                         "from --prev forever and silently mis-key the drop-rates scraper)")
    ap.add_argument("--force", action="store_true",
                    help="skip the shrink sanity check against --prev")
    args = ap.parse_args()

    if args.cache_id:
        cache_id, build, ts = args.cache_id, "?", "?"
    else:
        cache_id, build, ts = latest_cache_id()
    print(f"cache id={cache_id} build={build} ({ts})", file=sys.stderr)

    items = build_items(cache_id)
    print(f"walked {len(items)} collection-log items", file=sys.stderr)

    # wiki_page: seed from prev (preserves anchors, avoids re-hitting the wiki),
    # then look up only ids we don't already have. Overrides always win.
    prev_pages = {}
    if args.prev:
        try:
            for it in json.load(open(args.prev)):
                prev_pages[int(it["id"])] = it.get("wiki_page")
        except FileNotFoundError:
            pass
    overrides = {}
    if args.overrides:
        try:
            overrides = {int(k): v for k, v in json.load(open(args.overrides)).items()}
        except FileNotFoundError:
            pass

    # Sanity: the walk shrinking sharply vs the committed file means the cache layout
    # (or our hardcoded enum/param ids) changed, not that Jagex removed 20% of the log.
    if prev_pages and not args.force and len(items) < 0.8 * len(prev_pages):
        sys.exit(f"sanity: walked {len(items)} items but previous file has "
                 f"{len(prev_pages)} — cache layout may have changed (--force to override)")

    looked_up = misses = 0
    out = []
    for iid in sorted(items):
        rec = items[iid]
        if iid in overrides:
            page = overrides[iid]
        elif iid in prev_pages and prev_pages[iid]:
            page = prev_pages[iid]
        else:
            page = resolve_wiki_page(iid)
            looked_up += 1
            if page is None:
                misses += 1
                page = rec["name"]  # fallback; --strict refuses to persist this
                print(f"  ! no wiki page for {iid} ({rec['name']}), using name", file=sys.stderr)
            if args.sleep:
                time.sleep(args.sleep)
        out.append({"id": iid, "name": rec["name"], "tabs": rec["tabs"], "wiki_page": page})

    print(f"wiki lookups: {looked_up} ({misses} misses)", file=sys.stderr)
    if args.strict and misses:
        sys.exit(f"strict: {misses} wiki lookup miss(es) — not writing {args.out}")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {args.out} ({len(out)} items)", file=sys.stderr)


if __name__ == "__main__":
    main()
