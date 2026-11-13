const $=id=>document.getElementById(id);
const ids=["orderName","goods","china","fx","length","width","height","qty","weight","extra","cartonRate","crateRate","airRate"];
const money=n=>new Intl.NumberFormat("fr-FR",{maximumFractionDigits:0}).format(Number(n)||0);
const dec=n=>new Intl.NumberFormat("fr-FR",{minimumFractionDigits:2,maximumFractionDigits:2}).format(Number(n)||0);
const val=id=>Number($(id).value||0);
const getMode=()=>document.querySelector('input[name="mode"]:checked').value;
const getPack=()=>document.querySelector('input[name="pack"]:checked').value;
let history=JSON.parse(localStorage.getItem("cbmHistory")||"[]");
let rates=JSON.parse(localStorage.getItem("cbmRates")||"{}");
function loadRates(){$("cartonRate").value=rates.carton??"";$("crateRate").value=rates.crate??"";$("airRate").value=rates.air??""}
function calculate(){
 const mode=getMode(),pack=getPack();$("seaFields").hidden=mode!=="sea";$("airFields").hidden=mode!=="air";
 const l=val("length"),w=val("width"),h=val("height"),q=val("qty"),cbm=l*w*h/1e6*q;
 const goods=val("goods"),china=val("china"),fx=val("fx"),extra=val("extra");
 const rate=mode==="air"?val("airRate"):pack==="carton"?val("cartonRate"):val("crateRate");
 const freight=mode==="air"?val("weight")*rate:cbm*rate,usd=goods+china,converted=usd*fx,total=converted+freight+extra;
 $("volume").textContent=cbm.toFixed(4).replace(".",",")+" CBM";$("unitVolume").textContent=(l*w*h/1e6).toFixed(4).replace(".",",")+" CBM par colis";
 $("usdTotal").textContent=dec(usd)+" USD";$("converted").textContent=money(converted)+" FCFA";
 $("appliedRate").textContent=rate?money(rate)+(mode==="air"?" FCFA/kg":" FCFA/CBM"):"Tarif à saisir";
 $("freight").textContent=money(freight)+" FCFA";$("extraDisplay").textContent=money(extra)+" FCFA";$("grandTotal").textContent=money(total)+" FCFA";
 const dimensionsOK=mode==="air"||(l>0&&w>0&&h>0&&q>0),valid=fx>0&&rate>0&&dimensionsOK&&(mode!=="air"||val("weight")>0);
 $("saveOrder").disabled=!valid;$("copyResult").disabled=!valid;return {mode,pack,cbm,rate,freight,usd,converted,extra,total,valid}
}
function snapshot(){const c=calculate();return {id:Date.now(),date:new Date().toLocaleString("fr-FR"),name:$("orderName").value||"Commande sans nom",mode:c.mode,pack:c.pack,cbm:c.cbm,weight:val("weight"),goods:val("goods"),china:val("china"),fx:val("fx"),freight:c.freight,extra:c.extra,total:c.total,rate:c.rate}}
function report(o){return `COMMANDE : ${o.name}\nTransport : ${o.mode==="sea"?"Maritime":"Aérien"}\n${o.mode==="sea"?`Emballage : ${o.pack}\nVolume : ${o.cbm.toFixed(4)} CBM`:`Poids : ${o.weight} kg`}\nMarchandises : ${dec(o.goods)} USD\nExpédition fournisseur → transitaire : ${dec(o.china)} USD\nTaux : ${o.fx} FCFA/USD\nTransport international : ${money(o.freight)} FCFA\nAutres frais : ${money(o.extra)} FCFA\nTOTAL ESTIMÉ : ${money(o.total)} FCFA`}
function renderHistory(){
 const host=$("historyList");if(!history.length){host.innerHTML='<p class="empty">Aucune commande enregistrée.</p>';return}
 host.innerHTML=history.slice().reverse().map(o=>`<article class="history-item"><strong>${escapeHtml(o.name)}</strong><p>${escapeHtml(o.date)} · ${o.mode==="sea"?"Maritime · "+o.pack:"Aérien"}</p><p>${o.mode==="sea"?o.cbm.toFixed(4)+" CBM":o.weight+" kg"} · Achat : ${dec(o.goods+o.china)} USD</p><div class="sum">${money(o.total)} FCFA</div><button class="secondary" data-copy="${o.id}">Copier</button><button class="ghost" data-delete="${o.id}">Supprimer</button></article>`).join("");
 host.querySelectorAll("[data-copy]").forEach(b=>b.onclick=()=>{const o=history.find(x=>x.id==b.dataset.copy);copy(report(o))});
 host.querySelectorAll("[data-delete]").forEach(b=>b.onclick=()=>{history=history.filter(x=>x.id!=b.dataset.delete);persistHistory();renderHistory()})
}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]))}
function persistHistory(){localStorage.setItem("cbmHistory",JSON.stringify(history))}
async function copy(t){try{await navigator.clipboard.writeText(t);$("feedback").textContent="Rapport copié.";setTimeout(()=>$("feedback").textContent="",2500)}catch(e){$("feedback").textContent="Copie non disponible sur ce navigateur."}}
ids.forEach(id=>$(id).addEventListener("input",calculate));
document.querySelectorAll('input[name="mode"],input[name="pack"]').forEach(x=>x.addEventListener("change",calculate));
document.querySelectorAll(".tab").forEach(b=>b.addEventListener("click",()=>{document.querySelectorAll(".tab,.page").forEach(x=>x.classList.remove("active"));b.classList.add("active");$(b.dataset.tab).classList.add("active");if(b.dataset.tab==="history")renderHistory()}));
$("saveRates").onclick=()=>{rates={carton:$("cartonRate").value,crate:$("crateRate").value,air:$("airRate").value};localStorage.setItem("cbmRates",JSON.stringify(rates));$("settingsFeedback").textContent="Tarifs enregistrés sur ce téléphone.";calculate()};
$("saveOrder").onclick=()=>{const o=snapshot();history.push(o);persistHistory();$("feedback").textContent="Commande enregistrée dans l'historique."};
$("copyResult").onclick=()=>copy(report(snapshot()));
$("reset").onclick=()=>{["goods","china","extra"].forEach(id=>$(id).value="0");$("orderName").value="Nouvelle commande";$("length").value="100";$("width").value="50";$("height").value="40";$("qty").value="1";$("weight").value="10";document.querySelector('input[name="mode"][value="sea"]').checked=true;document.querySelector('input[name="pack"][value="carton"]').checked=true;calculate()};
$("clearHistory").onclick=()=>{if(confirm("Effacer tout l'historique de ce téléphone ?")){history=[];persistHistory();renderHistory()}};
$("network").textContent=navigator.onLine?"En ligne":"Hors ligne";
window.addEventListener("online",()=>$("network").textContent="En ligne");window.addEventListener("offline",()=>$("network").textContent="Hors ligne");
loadRates();calculate();if("serviceWorker" in navigator)window.addEventListener("load",()=>navigator.serviceWorker.register("./sw.js").catch(()=>{}));