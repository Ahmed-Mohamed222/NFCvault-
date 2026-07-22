const {useEffect,useMemo,useRef,useState}=React;
const nativeBridge=typeof window.NfcBridge!=="undefined";
const CARD_KEY="nfc_saved_cards_v3", LEGACY_CARD_KEY="nfc_saved_cards", PREF_KEY="nfc_vault_preferences_v1";

const CATEGORIES={
  access:{label:"Access",description:"Workplace, campus, residential, parking and gym credentials."},
  transit:{label:"Transit",description:"Public transport cards, tickets and mobility passes."},
  payment:{label:"Payment",description:"Contactless bank, wallet, gift and stored-value cards."},
  hotel:{label:"Hotel key",description:"Hotel room keys and hospitality credentials."},
  identity:{label:"Identity",description:"Employee, student, library, membership and ID cards."},
  loyalty:{label:"Loyalty",description:"Rewards, membership, gift and customer cards."},
  ticket:{label:"Tickets",description:"Event tickets, boarding passes and venue entry credentials."},
  health:{label:"Health",description:"Healthcare, insurance and appointment identifiers."},
  mobility:{label:"Mobility",description:"Vehicle, charging, bike-share and parking credentials."},
  smart_home:{label:"Smart home",description:"Compatible home access and automation tags."},
  nfc_tag:{label:"NFC tag",description:"Links, contacts, text, locations and automation records."},
  product:{label:"Product / asset",description:"Authentication, inventory and asset-tracking tags."},
  other:{label:"Other",description:"Other compatible NFC-A, NFC-B, NFC-F or NFC-V items."}
};
const CARD_TYPES={
  mifare_classic:"MIFARE Classic",mifare_plus:"MIFARE Plus",mifare_ultralight:"NTAG / Ultralight",
  desfire:"MIFARE DESFire",emv:"EMV payment",ndef:"NDEF tag",ndef_formatable:"Blank NFC tag",
  isodep:"ISO-DEP smart card",nfc_barcode:"NFC barcode",nfcv:"NFC-V / ISO 15693",
  nfcf:"FeliCa / NFC-F",nfca:"NFC-A",nfcb:"NFC-B",generic:"Unknown NFC"
};
const SECURE_CATEGORIES=new Set(["access","transit","payment","hotel","identity","health","mobility"]);
const PRESETS=[
  {id:"uri",code:"LINK",label:"Website",placeholder:"https://example.com",hint:"Opens a web address."},
  {id:"text",code:"TEXT",label:"Text note",placeholder:"Type a short note",hint:"Stores plain text.",multiline:true},
  {id:"tel",code:"CALL",label:"Phone",placeholder:"+20 100 000 0000",hint:"Opens the phone dialer."},
  {id:"mailto",code:"MAIL",label:"Email",placeholder:"hello@example.com",hint:"Starts a new email."},
  {id:"geo",code:"MAP",label:"Location",placeholder:"30.0444,31.2357",hint:"Opens a map location."},
  {id:"mime",code:"VCARD",label:"Contact card",placeholder:"BEGIN:VCARD\nVERSION:3.0\nFN:Name\nTEL:+201000000000\nEND:VCARD",hint:"Stores a standard vCard.",multiline:true,mime:"text/vcard"}
];

function safeParse(value,fallback){try{return JSON.parse(value)}catch(e){return fallback}}
function inferCategory(card){
  if(card.category&&CATEGORIES[card.category])return card.category;
  if(card.cardType==="emv")return"payment";
  if(card.cardType==="nfcf"&&String(card.felicaSystem||"").toLowerCase().includes("transit"))return"transit";
  if(card.cardType==="ndef"||card.cardType==="ndef_formatable"||card.cardType==="mifare_ultralight")return"nfc_tag";
  if(card.cardType==="desfire"||card.cardType==="mifare_classic")return"access";
  if(card.cardType==="nfcv"||card.cardType==="nfc_barcode")return"product";
  return"other";
}
function normalizeCard(input){
  const card={...input};
  card.id=String(card.id||("card_"+Date.now()+"_"+Math.random().toString(36).slice(2,8)));
  card.cardType=CARD_TYPES[card.cardType]?card.cardType:"generic";
  card.category=inferCategory(card);
  card.scannedAt=card.scannedAt||new Date().toISOString();
  card.name=String(card.name||card.tagType||CATEGORIES[card.category].label||"NFC item").slice(0,100);
  card.technologies=Array.isArray(card.technologies)?card.technologies.slice(0,20):[];
  return card;
}
function loadValue(key,fallback){
  try{const raw=nativeBridge?window.NfcBridge.loadData(key):localStorage.getItem(key);return raw?safeParse(raw,fallback):fallback}catch(e){return fallback}
}
function saveValue(key,value){
  try{const raw=JSON.stringify(value);if(nativeBridge)window.NfcBridge.saveData(key,raw);else localStorage.setItem(key,raw);return true}catch(e){return false}
}
function cardHasNdef(card){return Boolean(card&&(card.ndefMessageHex||(card.ndefRecords&&card.ndefRecords.length)))}
function formatDate(value){const date=new Date(value);return Number.isNaN(date.getTime())?"Unknown":date.toLocaleString()}
function typeLabel(card){return CARD_TYPES[card.cardType]||CARD_TYPES.generic}
function categoryLabel(card){return(CATEGORIES[card.category]||CATEGORIES.other).label}
function capabilities(card,hce){
  const secure=SECURE_CATEGORIES.has(card.category);
  return[
    {title:"Scan and identify",ok:true,text:"Technology and publicly readable data can be inspected."},
    {title:"Store in this vault",ok:true,text:"Saved locally with Android Keystore encryption."},
    {title:"Write to another tag",ok:cardHasNdef(card),text:cardHasNdef(card)?"Its standard NDEF message can be copied to a writable tag.":"No writable standard NDEF message was found."},
    {title:"Share by phone",ok:cardHasNdef(card)&&hce&&!secure,text:secure?"Protected credentials require an official issuer or wallet integration.":cardHasNdef(card)&&hce?"The standard NDEF message can be shared with compatible readers.":"This device or item does not support NDEF sharing."}
  ];
}
function demoCard(){return normalizeCard({name:"Museum membership",tagType:"NTAG 215",cardType:"mifare_ultralight",serial:"04:A1:8D:2C:91:70:80",manufacturer:"NXP Semiconductors",technologies:["NfcA","MifareUltralight","Ndef"],isWritable:true,maxSize:504,ndefMessageHex:"D1011555016E66637661756C742E6578616D706C652F64656D6F",ndefRecords:[{recordType:"URI",decoded:"https://nfcvault.example/demo",payloadSize:21}]})}

const listeners=[];
function onCardEvent(callback){listeners.push(callback);return()=>{const i=listeners.indexOf(callback);if(i>=0)listeners.splice(i,1)}}
window.NfcCallbacks={
  onCardRead:json=>{const card=normalizeCard(safeParse(json,{}));if(window.__scanOk){const fn=window.__scanOk;window.__scanOk=null;window.__scanError=null;fn(card)}else listeners.forEach(fn=>fn(card,null))},
  onReadError:message=>{if(window.__scanError){const fn=window.__scanError;window.__scanOk=null;window.__scanError=null;fn(message)}else listeners.forEach(fn=>fn(null,message))},
  onWriteComplete:json=>window.__writeOk&&window.__writeOk(safeParse(json,{})),
  onWriteError:message=>window.__writeError&&window.__writeError(message),
  onExportComplete:json=>window.__exportDone&&window.__exportDone(safeParse(json,{})),
  onNfcStatus:value=>window.__setNfcStatus&&window.__setNfcStatus(value)
};

function TypeBadge({card,category=false}){return <span className={"badge "+(category?"category":"")}>{category?categoryLabel(card):typeLabel(card)}</span>}
function VaultCard({card,selected,onClick}){return <button className={"vault-card "+(selected?"selected":"")} onClick={onClick} aria-pressed={selected}>
  <span className="card-top"><span style={{minWidth:0}}><span className="card-name">{card.name}</span><span className="serial">{card.serial||"No public serial"}</span></span><TypeBadge card={card} category/></span>
  <span className="techs"><TypeBadge card={card}/>{card.technologies.slice(0,3).map(item=><span className="tech" key={item}>{item}</span>)}</span>
</button>}
function Empty({title,text,action}){return <div className="empty"><div className="empty-mark" aria-hidden="true">NV</div><strong>{title}</strong><div className="hint" style={{margin:"7px auto 16px",maxWidth:380}}>{text}</div>{action}</div>}
function Capabilities({card,hce}){return <div className="cap-list">{capabilities(card,hce).map(cap=><div className={"cap "+(cap.ok?"":"no")} key={cap.title}><div className="cap-mark" aria-hidden="true">{cap.ok?"+":"-"}</div><div><div className="cap-title">{cap.title}</div><div className="cap-text">{cap.text}</div></div></div>)}</div>}

function CardDetail({card,hce,isSaved,sharingId,onSave,onDelete,onUpdate,onExport,onCopy,onShare}){
  if(!card)return null;
  const isSharing=sharingId===card.id;
  const fields=[
    ["Technology",typeLabel(card)],["Detected as",card.tagType],["Serial",card.serial],["Manufacturer",card.manufacturer],
    ["ATQA",card.atqa],["SAK",card.sak],["Capacity",card.maxSize!=null?card.maxSize+" bytes":card.totalMemory],
    ["Writable",card.isWritable==null?null:(card.isWritable?"Yes":"No")],["Scanned",formatDate(card.scannedAt)]
  ].filter(row=>row[1]!=null&&row[1]!=="");
  const rename=()=>{const value=prompt("Name this item",card.name);if(value&&value.trim())onUpdate({...card,name:value.trim().slice(0,100)})};
  return <section className="panel detail" aria-label={card.name+" details"}>
    <div className="detail-hero"><div className="row"><TypeBadge card={card} category/><TypeBadge card={card}/></div><h2>{card.name}</h2><button className="ghost" style={{marginTop:12,minHeight:38,padding:"6px 11px"}} onClick={rename}>Rename</button></div>
    <div className="detail-body">
      <label className="form-label" htmlFor="category-select" style={{marginTop:0}}>Card category</label>
      <select id="category-select" className="field" value={card.category} onChange={e=>onUpdate({...card,category:e.target.value})}>{Object.entries(CATEGORIES).map(([id,item])=><option value={id} key={id}>{item.label}</option>)}</select>
      <div className="meta-grid">{fields.map(([key,value])=><div className="meta" key={key}><div className="meta-key">{key}</div><div className="meta-value">{String(value)}</div></div>)}</div>
      <div className="section-title"><h3>What you can do</h3></div><Capabilities card={card} hce={hce}/>
      {card.ndefRecords&&card.ndefRecords.length>0&&<><div className="section-title" style={{marginTop:18}}><h3>NDEF records</h3></div><div className="records">{card.ndefRecords.map((record,index)=><div className="record" key={index}><strong>{record.recordType||"Record"}</strong><span>{record.decoded||"Binary record"}</span></div>)}</div></>}
      <div className="row" style={{marginTop:16}}>
        {!isSaved&&<button className="primary" onClick={()=>onSave(card)}>Save securely</button>}
        {isSaved&&cardHasNdef(card)&&<button className="secondary" onClick={()=>onCopy(card)}>{SECURE_CATEGORIES.has(card.category)?"Copy public NDEF":"Copy to another tag"}</button>}
        {cardHasNdef(card)&&hce&&!SECURE_CATEGORIES.has(card.category)&&<button className="secondary" onClick={()=>onShare(card)}>{isSharing?"Stop sharing":"Share by phone"}</button>}
        <button className="ghost" onClick={()=>onExport(card)}>Export</button>
        {isSaved&&<button className="danger" onClick={()=>onDelete(card.id)}>Delete</button>}
      </div>
      <details><summary>Advanced technical data</summary><pre className="raw">{JSON.stringify(card,null,2)}</pre></details>
    </div>
  </section>
}

function Home({status,cards,scanning,setScanning,scanned,setScanned,startScan,openSettings,...detailProps}){
  if(scanning)return <div className="panel scanner"><div><div className="scan-orb" aria-hidden="true"><div className="scan-core">NFC</div></div><h2>Hold the item near your phone</h2><p className="hint">Move it slowly around the back of the device until you feel a vibration or see a result.</p><button className="ghost" onClick={()=>{window.__scanOk=null;window.__scanError=null;setScanning(false)}}>Cancel scan</button></div></div>;
  if(scanned)return <><div className="page-head"><div><div className="eyebrow">Scan complete</div><h1>Item detected</h1><p>Review what the phone could read before saving it.</p></div><button className="secondary" onClick={()=>setScanned(null)}>Scan another</button></div><div className="two"><CardDetail card={scanned} {...detailProps}/><div className="panel pad"><div className="section-title"><h2>Privacy note</h2></div><p className="hint">Only publicly readable NFC data is shown. Saving is optional. Protected payment, access, hotel and transit credentials cannot be duplicated by this app.</p></div></div></>;
  return <div className="grid two"><section className="panel hero"><div className="hero-copy"><div className="eyebrow">Your contactless companion</div><h2>Know what is on your <span>NFC item.</span></h2><p>Scan supported cards and tags, organize permitted data in an encrypted vault, create useful NFC tags, and share standard NDEF records.</p><div className="row"><button className="primary" disabled={status==="unavailable"} onClick={startScan}>{nativeBridge?"Start NFC scan":"Try demo scan"}</button>{status==="disabled"&&<button className="secondary" onClick={openSettings}>Turn on NFC</button>}</div></div></section>
  <aside className="grid"><div className="quick"><div className="panel stat"><div className="stat-value">{cards.length}</div><div className="stat-label">Saved items</div></div><div className="panel stat"><div className="stat-value">{Object.keys(CATEGORIES).length}</div><div className="stat-label">Card categories</div></div></div><div className="panel pad"><div className="section-title"><h2>Designed for clarity</h2></div><div className="cap-list"><div className="cap"><div className="cap-mark">1</div><div><div className="cap-title">Scan</div><div className="cap-text">Hold a compatible NFC item near your phone.</div></div></div><div className="cap"><div className="cap-mark">2</div><div><div className="cap-title">Review</div><div className="cap-text">See its technology, readable data and limitations.</div></div></div><div className="cap"><div className="cap-mark">3</div><div><div className="cap-title">Choose</div><div className="cap-text">Save, export, write or share only when supported.</div></div></div></div></div></aside></div>
}

function Vault({cards,selected,setSelected,query,setQuery,filter,setFilter,...detailProps}){
  const filtered=useMemo(()=>cards.filter(card=>(filter==="all"||card.category===filter)&&(!query.trim()||[card.name,card.serial,card.tagType,card.manufacturer,categoryLabel(card)].some(v=>String(v||"").toLowerCase().includes(query.trim().toLowerCase())))),[cards,filter,query]);
  return <><div className="page-head"><div><div className="eyebrow">Encrypted on this device</div><h1>Your vault</h1><p>Search, categorize, back up and review your saved NFC items.</p></div></div><div className="two"><section><div className="toolbar"><input className="field search" aria-label="Search saved items" value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search name, serial or technology"/><button className="secondary" onClick={detailProps.onImport}>Import</button><button className="ghost" onClick={detailProps.onExportAll}>Export all</button></div><div className="filters" aria-label="Filter by category"><button className={"filter "+(filter==="all"?"active":"")} onClick={()=>setFilter("all")}>All {cards.length}</button>{Object.entries(CATEGORIES).map(([id,item])=>{const count=cards.filter(card=>card.category===id).length;return count?<button className={"filter "+(filter===id?"active":"")} onClick={()=>setFilter(id)} key={id}>{item.label} {count}</button>:null})}</div>
  {filtered.length?<div className="card-list">{filtered.map(card=><VaultCard card={card} key={card.id} selected={selected&&selected.id===card.id} onClick={()=>setSelected(selected&&selected.id===card.id?null:card)}/>)}</div>:<div className="panel"><Empty title={cards.length?"No matching items":"Your vault is empty"} text={cards.length?"Try a different search or category.":"Scan a compatible NFC card or tag, then choose Save securely."}/></div>}</section>
  {selected?<CardDetail card={selected} isSaved {...detailProps}/>:<div className="panel"><Empty title="Select an item" text="Choose a saved item to see its capabilities and technical details."/></div>}</div></>
}

function Create({cards,writeState,setWriteState,startWrite,startCopy,copySource,setCopySource}){
  const [type,setType]=useState("uri"),[value,setValue]=useState(""),[lang,setLang]=useState("en"),[mode,setMode]=useState(copySource?"copy":"new");
  const preset=PRESETS.find(item=>item.id===type)||PRESETS[0];
  const eligible=cards.filter(cardHasNdef);
  useEffect(()=>{if(copySource)setMode("copy")},[copySource&&copySource.id]);
  if(writeState.phase==="waiting")return <div className="panel write-state"><div className="spinner" aria-hidden="true"></div><div className="eyebrow">Destination scan</div><h2>{writeState.kind==="copy"?"Ready to copy onto another tag":"Ready for a writable tag"}</h2>{writeState.sourceName&&<div className="copy-source" style={{maxWidth:440,margin:"16px auto"}}><strong>Source: {writeState.sourceName}</strong><div className="hint">Only its standard public NDEF message will be written.</div></div>}<p className="hint">Hold the destination NFC card or tag near the back of your phone and keep it still.</p><button className="ghost" onClick={()=>{if(nativeBridge)window.NfcBridge.cancelWrite();window.__writeOk=null;window.__writeError=null;setWriteState({phase:"edit"})}}>Cancel</button></div>;
  if(writeState.phase==="done")return <div className="panel write-state"><div className="empty-mark" style={{color:"var(--aqua)"}}>OK</div><div className="eyebrow">{writeState.kind==="copy"?"Copy complete":"Write complete"}</div><h2>{writeState.result.verified?"Destination verified":"Destination written"}</h2><p className="hint">{writeState.result.bytes||0} bytes were written as {writeState.result.written||1} NDEF record(s). {writeState.result.verified?"The destination was read back and matches the source.":"Android completed the write; read-back verification was unavailable for this newly formatted tag."}</p><button className="primary" onClick={()=>setWriteState({phase:"edit"})}>{writeState.kind==="copy"?"Copy another":"Write another"}</button></div>;
  return <><div className="page-head"><div><div className="eyebrow">Standard NDEF tools</div><h1>Write or copy a tag</h1><p>Create new NFC content or copy a saved standard NDEF message onto another compatible writable card or tag.</p></div></div><div className="mode-tabs" role="tablist" aria-label="Write mode"><button className={mode==="new"?"active":""} role="tab" aria-selected={mode==="new"} onClick={()=>setMode("new")}>Write new content</button><button className={mode==="copy"?"active":""} role="tab" aria-selected={mode==="copy"} onClick={()=>setMode("copy")}>Copy saved card</button></div>
  {mode==="new"?<div className="grid two"><section className="panel pad"><div className="section-title"><h2>New NDEF record</h2><span className="badge">Step 1 of 2</span></div><div className="preset-grid">{PRESETS.map(item=><button className={"preset "+(type===item.id?"active":"")} onClick={()=>{setType(item.id);setValue("")}} key={item.id}><span className="preset-code">{item.code}</span>{item.label}</button>)}</div><label className="form-label" htmlFor="record-value">{preset.label}</label>{preset.multiline?<textarea id="record-value" className="field" rows="5" value={value} onChange={e=>setValue(e.target.value)} placeholder={preset.placeholder}/>:<input id="record-value" className="field" value={value} onChange={e=>setValue(e.target.value)} placeholder={preset.placeholder}/>}<div className="hint" style={{marginTop:6}}>{preset.hint}</div>{type==="text"&&<><label className="form-label" htmlFor="language">Language code</label><input id="language" className="field" style={{maxWidth:130}} value={lang} onChange={e=>setLang(e.target.value)} maxLength="12"/></>}<button className="primary" style={{width:"100%",marginTop:20}} disabled={!value.trim()} onClick={()=>startWrite([{type,value:value.trim(),lang,mime:preset.mime}])}>Scan destination tag</button></section><aside className="panel pad"><div className="section-title"><h2>How writing works</h2></div><div className="step-list"><div className="step-item">Choose a record type and enter the content.</div><div className="step-item">Scan a compatible writable destination tag.</div><div className="step-item">NFC Vault writes the message and verifies it when Android supports read-back.</div></div></aside></div>
  :<div className="grid two"><section className="panel pad"><div className="section-title"><h2>Select the saved source</h2><span className="badge">Step 1 of 2</span></div>{eligible.length?<div className="card-list">{eligible.map(card=><VaultCard card={card} key={card.id} selected={copySource&&copySource.id===card.id} onClick={()=>setCopySource(card)}/>)}</div>:<Empty title="No copyable cards saved" text="Scan and save a card or tag that contains a standard NDEF message first."/>}{copySource&&<><div className="copy-source"><strong>{copySource.name}</strong><div className="hint">{typeLabel(copySource)} · {copySource.ndefRecords&&copySource.ndefRecords.length||1} public NDEF record(s)</div></div><button className="primary" style={{width:"100%"}} onClick={()=>startCopy(copySource)}>Scan destination and copy</button></>}</section><aside className="grid"><div className="panel pad"><div className="section-title"><h2>Copy workflow</h2></div><div className="step-list"><div className="step-item">Scan and save the source in your encrypted vault.</div><div className="step-item">Select it here, then scan a writable destination.</div><div className="step-item">NFC Vault copies the public NDEF message and verifies compatible destinations.</div></div></div><div className="notice warn"><strong>Public content only.</strong><br/>Payment, access, transit, identity, and hotel security credentials cannot be copied. Their official issuer or wallet integration is required.</div></aside></div>}</>
}

function Guide({prefs,setPrefs}){const matrix=[
  ["NDEF tags and stickers","Yes","Yes","Yes"],["Access and hotel cards","Limited","Issuer only","No"],["Transit cards","Limited","Issuer only","No"],["Contactless payment cards","Basic type only","Official wallet","No"],["FeliCa / NFC-F","Limited","Depends","No"],["NFC-V asset tags","Yes","Depends","No"],["Bluetooth, UWB, LF RFID","No","No","No"]
];return <><div className="page-head"><div><div className="eyebrow">Support and accessibility</div><h1>Compatibility guide</h1><p>NFC is a family of technologies. Phone hardware, Android, the card issuer and encryption all affect what is possible.</p></div></div><div className="grid two"><section className="grid"><div className="panel pad"><div className="section-title"><h2>Common card categories</h2></div><div className="category-grid">{Object.values(CATEGORIES).map(item=><div className="category-card" key={item.label}><strong>{item.label}</strong><p>{item.description}</p></div>)}</div></div><div className="panel pad" style={{overflowX:"auto"}}><div className="section-title"><h2>What support means</h2></div><table className="matrix"><thead><tr><th>Technology</th><th>Scan</th><th>Use by phone</th><th>Copy</th></tr></thead><tbody>{matrix.map(row=><tr key={row[0]}>{row.map((cell,index)=><td className={index?cell==="Yes"?"yes":cell==="No"?"no":"limited":""} key={index}>{cell}</td>)}</tr>)}</tbody></table></div></section>
  <aside className="grid"><div className="panel pad"><div className="section-title"><h2>Display preferences</h2></div>{[["largeText","Larger text","Increase text throughout the app."],["highContrast","High contrast","Strengthen borders and secondary text."],["reduceMotion","Reduce motion","Minimize scanning and transition animation."]].map(([key,title,text])=><div className="switch-row" key={key}><div><strong>{title}</strong><div className="hint">{text}</div></div><button className={"switch "+(prefs[key]?"on":"")} role="switch" aria-checked={prefs[key]} aria-label={title} onClick={()=>setPrefs({...prefs,[key]:!prefs[key]})}><span/></button></div>)}</div><div className="panel pad"><div className="section-title"><h2>Privacy and safety</h2></div><p className="hint">Vault data is encrypted locally with a key held by Android Keystore. Backups are disabled. The app works offline and does not request internet access.</p><div className="notice good" style={{marginTop:14}}>NFC Vault shares only standard NDEF records. It does not emulate payment cards or bypass protected credentials.</div></div></aside></div></>}

function App(){
  const [tab,setTab]=useState("home"),[cards,setCards]=useState([]),[selected,setSelected]=useState(null),[scanned,setScanned]=useState(null),[scanning,setScanning]=useState(false),[status,setStatus]=useState(nativeBridge?"ready":"demo"),[hce,setHce]=useState(false),[toast,setToast]=useState(null),[query,setQuery]=useState(""),[filter,setFilter]=useState("all"),[sharing,setSharing]=useState(null),[writeState,setWriteState]=useState({phase:"edit"}),[copySource,setCopySource]=useState(null),[prefs,setPrefs]=useState({largeText:false,highContrast:false,reduceMotion:false});
  const importRef=useRef(null),toastRef=useRef(null);
  const showToast=(message,error=false)=>{clearTimeout(toastRef.current);setToast({message,error});toastRef.current=setTimeout(()=>setToast(null),3300)};
  useEffect(()=>{let loaded=loadValue(CARD_KEY,[]);if(!Array.isArray(loaded)||loaded.length===0){const legacy=loadValue(LEGACY_CARD_KEY,[]);if(Array.isArray(legacy)&&legacy.length){loaded=legacy;saveValue(CARD_KEY,legacy);if(nativeBridge)window.NfcBridge.removeData(LEGACY_CARD_KEY);else localStorage.removeItem(LEGACY_CARD_KEY)}}setCards(Array.isArray(loaded)?loaded.slice(0,500).map(normalizeCard):[]);const p=loadValue(PREF_KEY,{});setPrefs(old=>({...old,...p}));if(nativeBridge){try{if(!window.NfcBridge.isNfcAvailable())setStatus("unavailable");else setStatus(window.NfcBridge.isNfcEnabled()?"ready":"disabled");setHce(window.NfcBridge.isEmulationAvailable())}catch(e){setStatus("unavailable")}}},[]);
  useEffect(()=>{document.body.classList.toggle("large-text",prefs.largeText);document.body.classList.toggle("high-contrast",prefs.highContrast);document.body.classList.toggle("reduce-motion",prefs.reduceMotion);saveValue(PREF_KEY,prefs)},[prefs]);
  useEffect(()=>{window.__setNfcStatus=setStatus;const unsubscribe=onCardEvent((card,error)=>{if(error){showToast(error,true);return}setScanned(card);setScanning(false);setTab("home");showToast("NFC item detected")});return()=>{window.__setNfcStatus=null;unsubscribe()}},[]);
  const persist=next=>{setCards(next);if(!saveValue(CARD_KEY,next))showToast("Could not save securely",true)};
  const saveCard=card=>{const normalized=normalizeCard(card);persist([normalized,...cards.filter(item=>item.id!==normalized.id)]);setScanned(normalized);showToast("Saved securely")};
  const updateCard=card=>{const normalized=normalizeCard(card);if(cards.some(item=>item.id===normalized.id)){persist(cards.map(item=>item.id===normalized.id?normalized:item));setSelected(normalized)}else setScanned(normalized)};
  const deleteCard=id=>{if(!confirm("Delete this item from the encrypted vault?"))return;persist(cards.filter(item=>item.id!==id));setSelected(null);if(copySource&&copySource.id===id)setCopySource(null);if(sharing===id){window.NfcBridge.stopEmulation();setSharing(null)}showToast("Item deleted")};
  const startScan=()=>{if(status==="disabled"){showToast("Turn on NFC first",true);return}setScanning(true);setScanned(null);if(nativeBridge){window.NfcBridge.startReadMode();window.__scanOk=card=>{setScanned(card);setScanning(false);showToast("NFC item detected")};window.__scanError=message=>{setScanning(false);showToast(message,true)}}else setTimeout(()=>{setScanned(demoCard());setScanning(false);showToast("Demo item detected")},1500)};
  const openSettings=()=>nativeBridge&&window.NfcBridge.openNfcSettings();
  const exportJson=(name,data)=>{const json=JSON.stringify(data,null,2);if(nativeBridge){window.__exportDone=result=>{showToast(result.message||"Export finished",!result.success);window.__exportDone=null};window.NfcBridge.exportJson(name,json)}else{const blob=new Blob([json],{type:"application/json"}),url=URL.createObjectURL(blob),a=document.createElement("a");a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);showToast("Export downloaded")}};
  const exportCard=card=>exportJson(card.name.replace(/[^a-z0-9_-]/gi,"_")+".json",card);
  const exportAll=()=>cards.length?exportJson("nfc-vault-backup.json",cards):showToast("The vault is empty",true);
  const importCards=event=>{const file=event.target.files&&event.target.files[0];event.target.value="";if(!file)return;if(file.size>5_000_000){showToast("Import is larger than 5 MB",true);return}const reader=new FileReader();reader.onload=()=>{const parsed=safeParse(reader.result,null),items=Array.isArray(parsed)?parsed:[parsed];if(!parsed||!items.every(item=>item&&typeof item==="object")){showToast("This is not a valid NFC Vault file",true);return}const incoming=items.slice(0,500).map(item=>normalizeCard({...item,id:null}));persist([...incoming,...cards].slice(0,500));showToast("Imported "+incoming.length+" item"+(incoming.length===1?"":"s"))};reader.onerror=()=>showToast("Could not read that file",true);reader.readAsText(file)};
  const armWrite=(starter,context)=>{window.__writeOk=result=>{setWriteState({phase:"done",result,...context});window.__writeOk=null;window.__writeError=null;showToast(context.kind==="copy"?"Card copied":"Tag written")};window.__writeError=message=>{setWriteState({phase:"edit"});window.__writeOk=null;window.__writeError=null;showToast(message,true)};const response=starter();if(String(response).startsWith("ERR:")){window.__writeError(String(response).slice(4));return}setWriteState({phase:"waiting",...context});setTab("create")};
  const startWrite=records=>nativeBridge?armWrite(()=>window.NfcBridge.startNdefWrite(JSON.stringify(records)),{kind:"new"}):(setWriteState({phase:"waiting",kind:"new"}),setTimeout(()=>setWriteState({phase:"done",kind:"new",result:{bytes:42,written:records.length,verified:true}}),1300));
  const startCopy=card=>{const context={kind:"copy",sourceName:card.name};if(!nativeBridge){setWriteState({phase:"waiting",...context});setTimeout(()=>setWriteState({phase:"done",...context,result:{bytes:32,written:1,verified:true}}),1300);return}if(card.ndefMessageHex){armWrite(()=>window.NfcBridge.startRawNdefWrite(card.ndefMessageHex),context);return}const records=(card.ndefRecords||[]).map(record=>record.recordType==="URI"?{type:"uri",value:record.decoded}:record.recordType==="TEXT"?{type:"text",value:record.decoded,lang:"en"}:record.recordType==="MIME"?{type:"mime",value:record.decoded,mime:record.mimeType||"text/plain"}:null).filter(Boolean);if(!records.length){showToast("This NDEF record type cannot be copied",true);return}armWrite(()=>window.NfcBridge.startNdefWrite(JSON.stringify(records)),context)};
  const share=card=>{if(!nativeBridge)return;if(sharing===card.id){window.NfcBridge.stopEmulation();setSharing(null);showToast("NDEF sharing stopped")}else{window.NfcBridge.startEmulation(JSON.stringify(card));setSharing(card.id);showToast("NDEF sharing is active while the device is unlocked")}};
  const openCopy=card=>{setCopySource(card);setWriteState({phase:"edit"});setTab("create")};
  const common={hce,sharingId:sharing,onSave:saveCard,onDelete:deleteCard,onUpdate:updateCard,onExport:exportCard,onCopy:openCopy,onShare:share};
  return <div className="app"><header className="topbar"><div className="logo" aria-hidden="true">N</div><div><div className="brand">NFC Vault</div><div className="subtitle">Private, clear and compatible</div></div><div className={"status "+status} aria-live="polite"><span className="status-dot"/><span className="status-label">{status==="ready"?"NFC ready":status==="disabled"?"NFC is off":status==="unavailable"?"No NFC hardware":"Demo mode"}</span></div></header>
  <main className="main"><input ref={importRef} className="sr-only" type="file" accept="application/json,.json" onChange={importCards}/>{tab==="home"&&<Home status={status} cards={cards} scanning={scanning} setScanning={setScanning} scanned={scanned} setScanned={setScanned} startScan={startScan} openSettings={openSettings} isSaved={scanned&&cards.some(card=>card.id===scanned.id)} {...common}/>} {tab==="vault"&&<Vault cards={cards} selected={selected} setSelected={setSelected} query={query} setQuery={setQuery} filter={filter} setFilter={setFilter} onImport={()=>importRef.current&&importRef.current.click()} onExportAll={exportAll} {...common}/>} {tab==="create"&&<Create cards={cards} writeState={writeState} setWriteState={setWriteState} startWrite={startWrite} startCopy={startCopy} copySource={copySource} setCopySource={setCopySource}/>} {tab==="guide"&&<Guide prefs={prefs} setPrefs={setPrefs}/>}</main>
  <nav className="nav" aria-label="Main navigation">{[["home","Home"],["vault","Vault"],["create","Create"],["guide","Guide"]].map(([id,label])=><button className={tab===id?"active":""} aria-current={tab===id?"page":undefined} onClick={()=>setTab(id)} key={id}>{label}</button>)}</nav>{toast&&<div className={"toast "+(toast.error?"error":"")} role="status" aria-live="polite">{toast.message}</div>}</div>
}
ReactDOM.createRoot(document.getElementById("root")).render(<App/>);
