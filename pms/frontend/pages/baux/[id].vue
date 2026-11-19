<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { MODES, STATUTS_ECHEANCE, dateCourte, jourIso, usd } from '~/utils/libelles'

/** Fiche d'un bail : échéancier (facturer, régler), relevés de compteurs, inventaire type et états des lieux. */
useHead({ title: 'Bail' })

interface Bail { id: number, reference: string, reservationId: number, reservationReference: string, reservationStatut: string, etablissementId: number,
  etablissementNom: string, uniteId: number, uniteNumero: string, typeNom: string, clientNom: string, dateDebut: string, dateFin: string, loyerMensuel: number,
  chargesForfait: number, refacturerCompteurs: boolean, statut: string, conditions: string | null, duFacture: number }
interface Echeance { id: number, numero: number, periodeDebut: string, periodeFin: string, dateEcheance: string, loyer: number, charges: number,
  detailCharges: string | null, statut: string, venteReference: string | null, totalTtc: number | null }
interface Releve { id: number, type: 'EAU' | 'ELECTRICITE', date: string, index: number, echeanceId: number | null, notes: string | null }
interface Detail { bail: Bail, echeances: Echeance[], releves: Releve[] }
interface Element { piece: string, element: string, quantite: number }
interface Referentiel { banques: { id: number, nom: string }[], mobileMoney: { id: number, nom: string }[] }

const route = useRoute()
const api = useApi()
const session = useSessionStore()
const d = ref<Detail | null>(null)
const ref_ = ref<Referentiel | null>(null)
const erreur = ref('')
const succes = ref('')
const action = ref<number | null>(null)
const encaisse = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT'))
const peut = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))
const onglet = ref<'echeances' | 'compteurs' | 'logement'>('echeances')

async function charger() {
  try {
    d.value = await api<Detail>(`/baux/${route.params.id}`)
    useHead({ title: `Bail ${d.value.bail.reference}` })
  } catch (e) { erreur.value = messageErreur(e) }
}
onMounted(async () => {
  await charger()
  try { ref_.value = await api<Referentiel>('/referentiel') } catch { ref_.value = null }
  chargerModele()
})
const b = computed(() => d.value?.bail)
const aujourdhui = jourIso(new Date())

async function facturer(e: Echeance) {
  action.value = e.id
  erreur.value = ''
  try {
    const r = await api<Echeance>(`/baux/${b.value!.id}/echeances/${e.id}/facture`, { method: 'POST' })
    succes.value = r.statut === 'FACTUREE' ? `Échéance ${e.numero} facturée : vente ${r.venteReference}, ${usd(r.totalTtc)} TTC (créance ouverte dans M-FINAPP).`
      : `Échéance ${e.numero} en attente : M-FINAPP ne répond pas. Réessayez « Facturer » (aucun risque de double facture).`
    await charger()
  } catch (x) { erreur.value = messageErreur(x) } finally { action.value = null }
}
const reg = reactive({ ouvert: false, echeance: null as Echeance | null, mode: 'CAISSE', etablissementId: null as number | null, enCours: false })
function regler(e: Echeance) { Object.assign(reg, { ouvert: true, echeance: e, mode: 'CAISSE', etablissementId: null, enCours: false }) }
const etablissementsMode = computed(() => reg.mode === 'BANQUE' ? ref_.value?.banques ?? [] : reg.mode === 'MOBILE_MONEY' ? ref_.value?.mobileMoney ?? [] : [])
async function confirmerReglement() {
  reg.enCours = true
  try {
    await api(`/baux/${b.value!.id}/echeances/${reg.echeance!.id}/reglement`, { method: 'POST', body: { mode: reg.mode, etablissementId: reg.etablissementId } })
    succes.value = `Échéance ${reg.echeance!.numero} réglée.`
    reg.ouvert = false
    await charger()
  } catch (x) { erreur.value = messageErreur(x) } finally { reg.enCours = false }
}
async function resilier() {
  const motif = await useDialogues().saisir({ titre: `Résilier le bail ${b.value!.reference} ?`, message: 'Les échéances futures non facturées sont retirées ; le départ se fait ensuite depuis le séjour.',
    libelle: 'Motif', bouton: 'Résilier', nature: 'danger', obligatoire: true })
  if (!motif) return
  try { await api(`/baux/${b.value!.id}/resiliation`, { method: 'POST', body: { motif } }); await charger() } catch (x) { erreur.value = messageErreur(x) }
}

// ── Compteurs ───────────────────────────────────────────────────────────────────────────────────────────────
const rel = reactive({ type: 'EAU' as 'EAU' | 'ELECTRICITE', date: aujourdhui, index: null as number | null, enCours: false })
const relevesPar = (t: string) => (d.value?.releves ?? []).filter(r => r.type === t).slice().reverse()
async function ajouterReleve() {
  rel.enCours = true
  try {
    await api(`/unites/${b.value!.uniteId}/releves`, { method: 'POST', body: { type: rel.type, date: rel.date, index: rel.index } })
    rel.index = null
    await charger()
  } catch (x) { erreur.value = messageErreur(x) } finally { rel.enCours = false }
}
async function retirerReleve(r: Releve) {
  if (!(await useDialogues().confirmer({ titre: 'Retirer ce relevé ?', bouton: 'Retirer', nature: 'danger' }))) return
  try { await api(`/releves/${r.id}`, { method: 'DELETE' }); await charger() } catch (x) { erreur.value = messageErreur(x) }
}

// ── Inventaire type ─────────────────────────────────────────────────────────────────────────────────────────
const modele = ref<Element[]>([])
async function chargerModele() {
  if (!b.value) return
  try { modele.value = await api<Element[]>(`/unites/${b.value.uniteId}/inventaire`) } catch { modele.value = [] }
}
async function enregistrerModele() {
  try {
    modele.value = await api<Element[]>(`/unites/${b.value!.uniteId}/inventaire`, { method: 'PUT', body: modele.value.filter(e => e.piece.trim() && e.element.trim()) })
    succes.value = 'Inventaire type enregistré.'
  } catch (x) { erreur.value = messageErreur(x) }
}
const MODELE_DEPART: Element[] = [
  { piece: 'Séjour', element: 'Canapé', quantite: 1 }, { piece: 'Séjour', element: 'Télévision', quantite: 1 }, { piece: 'Séjour', element: 'Table et chaises', quantite: 1 },
  { piece: 'Cuisine', element: 'Réfrigérateur', quantite: 1 }, { piece: 'Cuisine', element: 'Plaques de cuisson', quantite: 1 }, { piece: 'Cuisine', element: 'Vaisselle (lot)', quantite: 1 },
  { piece: 'Chambre', element: 'Lit et matelas', quantite: 1 }, { piece: 'Chambre', element: 'Climatiseur', quantite: 1 }, { piece: 'Chambre', element: 'Armoire', quantite: 1 },
  { piece: 'Salle de bain', element: 'Douche et robinetterie', quantite: 1 }, { piece: 'Salle de bain', element: 'Lavabo et miroir', quantite: 1 },
]
</script>

<template>
  <div v-if="b" class="d-apparait">
    <section class="entete">
      <span class="entete__unite">{{ b.uniteNumero }}</span>
      <div class="entete__texte">
        <div class="page-eyebrow">Bail {{ b.reference }}</div>
        <h1 class="entete__titre">{{ b.clientNom }}</h1>
        <div class="entete__infos">
          <span><v-icon icon="mdi-home-outline" size="16" />{{ b.typeNom }} · {{ b.etablissementNom }}</span>
          <span><v-icon icon="mdi-calendar-range" size="16" />{{ dateCourte(b.dateDebut) }} → {{ dateCourte(b.dateFin) }}</span>
          <span><v-icon icon="mdi-cash" size="16" />{{ usd(b.loyerMensuel) }} / mois{{ Number(b.chargesForfait) ? ` + ${usd(b.chargesForfait)}` : '' }}</span>
        </div>
      </div>
      <div class="entete__actions">
        <span class="pastille" :style="{ '--c': b.statut === 'ACTIF' ? 'var(--menage-propre)' : '#64748b' }">{{ b.statut === 'ACTIF' ? 'Actif' : b.statut === 'RESILIE' ? 'Résilié' : 'Terminé' }}</span>
        <v-btn variant="tonal" prepend-icon="mdi-book-open-page-variant-outline" :to="`/reservations/${b.reservationId}`">Séjour {{ b.reservationReference }}</v-btn>
        <v-btn v-if="b.statut === 'ACTIF' && session.a('DIR_HEBERGEMENT', 'ADMIN')" variant="text" color="error" @click="resilier">Résilier</v-btn>
      </div>
    </section>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <div class="onglets mb-4" role="tablist">
      <button v-for="o in ([{ v: 'echeances', t: 'Échéancier', i: 'mdi-calendar-month-outline' }, { v: 'compteurs', t: 'Compteurs', i: 'mdi-gauge' }, { v: 'logement', t: 'Logement', i: 'mdi-clipboard-list-outline' }] as const)"
        :key="o.v" type="button" role="tab" class="onglet" :class="{ 'onglet--actif': onglet === o.v }" @click="onglet = o.v"><v-icon :icon="o.i" size="17" />{{ o.t }}</button>
    </div>

    <section v-if="onglet === 'echeances'" class="d-carte">
      <div class="echeances">
        <div v-for="e in d!.echeances" :key="e.id" class="echeance" :class="{ 'echeance--due': e.statut === 'A_FACTURER' && e.dateEcheance <= aujourdhui }">
          <span class="echeance__num">{{ e.numero }}</span>
          <div class="echeance__texte">
            <strong>{{ dateCourte(e.periodeDebut) }} → {{ dateCourte(e.periodeFin) }}</strong>
            <small>Loyer {{ usd(e.loyer) }}<template v-if="Number(e.charges)"> · charges {{ usd(e.charges) }}</template><template v-if="e.detailCharges"> ({{ e.detailCharges }})</template></small>
          </div>
          <div class="echeance__droite">
            <div v-if="e.totalTtc" class="echeance__montant">{{ usd(e.totalTtc) }} <small>TTC</small></div>
            <span class="pastille" :style="{ '--c': STATUTS_ECHEANCE[e.statut]?.couleur }">{{ STATUTS_ECHEANCE[e.statut]?.libelle }}</span>
            <small v-if="e.venteReference" class="echeance__vente">{{ e.venteReference }}</small>
          </div>
          <div class="echeance__action">
            <v-btn v-if="encaisse && ['A_FACTURER', 'EN_ATTENTE'].includes(e.statut)" size="small" :variant="e.dateEcheance <= aujourdhui ? 'flat' : 'tonal'" color="primary"
              :loading="action === e.id" prepend-icon="mdi-file-document-arrow-right-outline" @click="facturer(e)">Facturer</v-btn>
            <v-btn v-else-if="encaisse && e.statut === 'FACTUREE'" size="small" variant="flat" color="success" prepend-icon="mdi-cash-check" @click="regler(e)">Encaisser</v-btn>
          </div>
        </div>
      </div>
    </section>

    <div v-else-if="onglet === 'compteurs'" class="compteurs">
      <section v-for="t in (['EAU', 'ELECTRICITE'] as const)" :key="t" class="d-carte">
        <div class="d-section">
          <span class="d-icone d-icone--sm" :style="{ '--c': t === 'EAU' ? '#0891b2' : '#d97706' }"><v-icon :icon="t === 'EAU' ? 'mdi-water-outline' : 'mdi-flash-outline'" size="18" /></span>
          <div><div class="d-section__titre">{{ t === 'EAU' ? 'Eau' : 'Électricité' }}</div><div class="d-section__sous">{{ t === 'EAU' ? 'm³' : 'kWh' }} · {{ b.refacturerCompteurs ? 'refacturé à l’échéance' : 'non refacturé' }}</div></div>
        </div>
        <div class="releves">
          <div v-for="r in relevesPar(t)" :key="r.id" class="releve">
            <span class="releve__date">{{ dateCourte(r.date) }}</span>
            <b class="releve__index">{{ Number(r.index).toLocaleString('fr-FR') }}</b>
            <v-spacer />
            <span v-if="r.echeanceId" class="pastille" style="--c: #2563eb">facturé</span>
            <v-btn v-else-if="peut" icon="mdi-delete-outline" size="x-small" variant="text" aria-label="Retirer" @click="retirerReleve(r)" />
          </div>
          <p v-if="!relevesPar(t).length" class="text-body-2 text-medium-emphasis mb-0">Aucun relevé. Le premier (à l’entrée) sert de référence.</p>
        </div>
      </section>
      <section v-if="peut" class="d-carte releve-form">
        <div class="d-section"><span class="d-icone d-icone--sm"><v-icon icon="mdi-pencil-plus-outline" size="18" /></span><div class="d-section__titre">Nouveau relevé</div></div>
        <div class="px-5 pb-5">
          <v-btn-toggle v-model="rel.type" mandatory divided variant="outlined" density="comfortable" class="mb-3 w-100">
            <v-btn value="EAU" class="flex-grow-1" prepend-icon="mdi-water-outline">Eau</v-btn><v-btn value="ELECTRICITE" class="flex-grow-1" prepend-icon="mdi-flash-outline">Électricité</v-btn>
          </v-btn-toggle>
          <v-text-field v-model="rel.date" type="date" label="Date du relevé" />
          <v-text-field v-model.number="rel.index" type="number" min="0" label="Index du compteur" inputmode="decimal" />
          <v-btn color="primary" variant="flat" block :loading="rel.enCours" :disabled="rel.index == null" @click="ajouterReleve">Enregistrer le relevé</v-btn>
        </div>
      </section>
    </div>

    <div v-else class="logement">
      <section class="d-carte">
        <div class="d-section"><span class="d-icone d-icone--sm" style="--c: #4338ca"><v-icon icon="mdi-clipboard-check-outline" size="18" /></span>
          <div><div class="d-section__titre">États des lieux</div><div class="d-section__sous">Entrée et sortie, photos, retenues sur caution</div></div></div>
        <div class="px-5 pb-5"><EtatDesLieux :reservation-id="b.reservationId" :peut="peut" /></div>
      </section>
      <section class="d-carte">
        <div class="d-section"><span class="d-icone d-icone--sm"><v-icon icon="mdi-format-list-checks" size="18" /></span>
          <div><div class="d-section__titre">Inventaire type de l’appartement {{ b.uniteNumero }}</div><div class="d-section__sous">Repris à chaque état des lieux d’entrée</div></div></div>
        <div class="px-5 pb-5">
          <div v-for="(e, i) in modele" :key="i" class="modele">
            <v-text-field v-model="e.piece" density="compact" hide-details placeholder="Pièce" :readonly="!peut" />
            <v-text-field v-model="e.element" density="compact" hide-details placeholder="Élément" :readonly="!peut" />
            <v-text-field v-model.number="e.quantite" type="number" min="1" density="compact" hide-details :readonly="!peut" class="modele__qte" />
            <v-btn v-if="peut" icon="mdi-close" size="x-small" variant="text" aria-label="Retirer" @click="modele.splice(i, 1)" />
          </div>
          <div v-if="peut" class="d-flex flex-wrap ga-2 mt-3">
            <v-btn size="small" variant="tonal" prepend-icon="mdi-plus" @click="modele.push({ piece: modele.at(-1)?.piece ?? 'Séjour', element: '', quantite: 1 })">Élément</v-btn>
            <v-btn v-if="!modele.length" size="small" variant="text" prepend-icon="mdi-auto-fix" @click="modele = MODELE_DEPART.map(x => ({ ...x }))">Partir d’un modèle</v-btn>
            <v-spacer /><v-btn size="small" color="primary" variant="flat" @click="enregistrerModele">Enregistrer</v-btn>
          </div>
        </div>
      </section>
    </div>

    <v-dialog v-model="reg.ouvert" max-width="460">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Encaisser l’échéance {{ reg.echeance?.numero }}</v-card-title>
        <v-card-text>
          <div class="reg__montant">{{ usd(reg.echeance?.totalTtc) }} <small>TTC</small></div>
          <v-btn-toggle v-model="reg.mode" mandatory divided variant="outlined" class="mb-3 w-100">
            <v-btn v-for="m in MODES" :key="m.value" :value="m.value" class="flex-grow-1" :prepend-icon="m.icone">{{ m.title.split(' ')[0] }}</v-btn>
          </v-btn-toggle>
          <v-select v-if="reg.mode !== 'CAISSE'" v-model="reg.etablissementId" :items="etablissementsMode" item-title="nom" item-value="id" :label="reg.mode === 'BANQUE' ? 'Banque' : 'Opérateur'" />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="reg.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="reg.enCours" :disabled="reg.mode !== 'CAISSE' && !reg.etablissementId" @click="confirmerReglement">Encaisser</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
  <v-alert v-else-if="erreur" type="error" variant="tonal">{{ erreur }}</v-alert>
</template>

<style scoped>
.entete { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; margin-bottom: 22px; }
.entete__unite { min-width: 72px; height: 72px; padding: 0 10px; border-radius: 22px; display: grid; place-items: center; font-weight: 800; font-size: 1.5rem;
  color: #fff; background: linear-gradient(135deg, #818cf8, #4338ca); box-shadow: 0 16px 30px -16px #4338ca; font-variant-numeric: tabular-nums; }
.entete__texte { flex: 1; min-width: 240px; }
.entete__titre { font-size: clamp(1.5rem, 2.8vw, 2rem); font-weight: 780; letter-spacing: -0.035em; margin: 0; }
.entete__infos { display: flex; flex-wrap: wrap; gap: 6px 18px; margin-top: 6px; font-size: 0.86rem; color: var(--d-ink-soft); }
.entete__infos span { display: inline-flex; align-items: center; gap: 6px; }
.entete__actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.onglets { display: inline-flex; gap: 4px; padding: 4px; border-radius: 14px; background: var(--d-surface-3); }
.onglet { display: inline-flex; align-items: center; gap: 6px; height: 38px; padding: 0 14px; border: 0; border-radius: 11px; cursor: pointer;
  font: inherit; font-size: 0.88rem; font-weight: 650; color: var(--d-ink-soft); background: transparent; }
.onglet--actif { color: var(--d-ink); background: var(--d-surface); box-shadow: var(--d-shadow-1); }

.echeances { padding: 8px 14px 14px; }
.echeance { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 14px; padding: 12px 8px; border-bottom: 1px dashed var(--d-line-strong); }
.echeance:last-child { border-bottom: 0; }
.echeance--due { background: color-mix(in srgb, #d97706 6%, transparent); border-radius: 14px; }
.echeance__num { width: 34px; height: 34px; border-radius: 11px; display: grid; place-items: center; font-weight: 800; font-size: 0.85rem; background: var(--d-surface-3); }
.echeance__texte strong { display: block; font-size: 0.9rem; }
.echeance__texte small { display: block; font-size: 0.78rem; color: var(--d-ink-soft); }
.echeance__droite { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.echeance__montant { font-weight: 760; font-variant-numeric: tabular-nums; }
.echeance__montant small { font-size: 0.7rem; color: var(--d-ink-faint); }
.echeance__vente { font-size: 0.72rem; color: var(--d-ink-faint); }
.echeance__action { min-width: 112px; text-align: right; }

.compteurs { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; align-items: start; }
.releves { padding: 4px 18px 16px; }
.releve { display: flex; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px dashed var(--d-line-strong); }
.releve:last-child { border-bottom: 0; }
.releve__date { font-size: 0.82rem; color: var(--d-ink-soft); width: 90px; }
.releve__index { font-size: 1.05rem; font-variant-numeric: tabular-nums; }

.logement { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.modele { display: grid; grid-template-columns: 1fr 1.4fr 70px auto; gap: 6px; align-items: center; margin-bottom: 6px; }
.reg__montant { font-size: 2rem; font-weight: 800; letter-spacing: -0.04em; margin-bottom: 14px; font-variant-numeric: tabular-nums; }
.reg__montant small { font-size: 0.9rem; color: var(--d-ink-faint); }

@media (max-width: 1279px) { .compteurs { grid-template-columns: repeat(2, minmax(0, 1fr)); } .releve-form { grid-column: 1 / -1; } }
@media (max-width: 959px) { .compteurs, .logement { grid-template-columns: 1fr; } .echeance { grid-template-columns: auto minmax(0, 1fr); } .echeance__droite, .echeance__action { grid-column: 2; align-items: flex-start; text-align: left; } }
</style>
