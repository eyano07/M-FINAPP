<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { dateCourte, jourIso, usd } from '~/utils/libelles'

/** Baux des appartements : loyer mensuel facturé par échéance (vente M-FINAPP à crédit), charges et compteurs refacturés. */
useHead({ title: 'Baux' })

interface Bail { id: number, reference: string, reservationId: number, reservationReference: string, etablissementNom: string, uniteNumero: string,
  typeNom: string, clientNom: string, dateDebut: string, dateFin: string, loyerMensuel: number, chargesForfait: number, statut: string,
  echeancesAFacturer: number, duFacture: number, prochaineEcheance: string | null }
interface Unite { id: number, numero: string, typeNom: string, etablissementNom: string, actif: boolean }
interface Client { id: number, nom: string, prenom: string | null, telephone: string | null }

const api = useApi()
const session = useSessionStore()
const etablissements = useEtablissementStore()
const baux = ref<Bail[]>([])
const statut = ref<'ACTIF' | 'TOUS'>('ACTIF')
const erreur = ref('')
const charge = ref(false)
const peut = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))

async function charger() {
  try {
    baux.value = await api<Bail[]>('/baux', { query: { etablissementId: etablissements.filtre, statut: statut.value === 'ACTIF' ? 'ACTIF' : undefined } })
    erreur.value = ''
  } catch (e) { erreur.value = messageErreur(e) } finally { charge.value = true }
}
onMounted(charger)
watch(statut, charger)

const totaux = computed(() => ({
  actifs: baux.value.filter(b => b.statut === 'ACTIF').length,
  loyers: baux.value.filter(b => b.statut === 'ACTIF').reduce((s, b) => s + Number(b.loyerMensuel), 0),
  aFacturer: baux.value.reduce((s, b) => s + b.echeancesAFacturer, 0),
  du: baux.value.reduce((s, b) => s + Number(b.duFacture), 0),
}))
const progression = (b: Bail) => {
  const d = new Date(b.dateDebut).getTime()
  const f = new Date(b.dateFin).getTime()
  return Math.max(0, Math.min(100, Math.round((Date.now() - d) * 100 / Math.max(1, f - d))))
}

// ── Nouveau bail ────────────────────────────────────────────────────────────────────────────────────────────
const unites = ref<Unite[]>([])
const clients = ref<Client[]>([])
const recherche = ref('')
const nb = reactive({ ouvert: false, uniteId: null as number | null, clientId: null as number | null, nouveau: false, nc: { nom: '', prenom: '', telephone: '', email: '' },
  dateDebut: jourIso(new Date()), dateFin: '', loyerMensuel: null as number | null, chargesForfait: 0, refacturerCompteurs: true, conditions: '', enCours: false, erreur: '' })
async function nouveau() {
  const fin = new Date(); fin.setMonth(fin.getMonth() + 6)
  Object.assign(nb, { ouvert: true, uniteId: null, clientId: null, nouveau: false, nc: { nom: '', prenom: '', telephone: '', email: '' },
    dateDebut: jourIso(new Date()), dateFin: jourIso(fin), loyerMensuel: null, chargesForfait: 0, refacturerCompteurs: true, conditions: '', enCours: false, erreur: '' })
  try { unites.value = (await api<Unite[]>('/unites', { query: { etablissementId: etablissements.filtre } })).filter(u => u.actif) } catch { unites.value = [] }
  chercher('')
}
let attente: ReturnType<typeof setTimeout> | null = null
function chercher(q: string) {
  if (attente) clearTimeout(attente)
  attente = setTimeout(async () => { try { clients.value = await api<Client[]>('/clients', { query: { q } }) } catch { clients.value = [] } }, 250)
}
watch(recherche, q => chercher(q ?? ''))
async function proposerLoyer() {
  if (!nb.uniteId || nb.loyerMensuel) return
  try { nb.loyerMensuel = (await api<{ prix: number | null }>('/tarifs/propose', { query: { uniteId: nb.uniteId, uniteDuree: 'MOIS', date: nb.dateDebut } })).prix } catch { /* saisie libre */ }
}
watch(() => nb.uniteId, proposerLoyer)
async function creer() {
  nb.erreur = ''
  nb.enCours = true
  try {
    const d = await api<{ bail: { id: number } }>('/baux', { method: 'POST', body: {
      uniteId: nb.uniteId, clientId: nb.nouveau ? null : nb.clientId, nouveauClient: nb.nouveau ? { ...nb.nc } : null,
      dateDebut: nb.dateDebut, dateFin: nb.dateFin, loyerMensuel: nb.loyerMensuel, chargesForfait: nb.chargesForfait || 0,
      refacturerCompteurs: nb.refacturerCompteurs, conditions: nb.conditions || null } })
    nb.ouvert = false
    await navigateTo(`/baux/${d.bail.id}`)
  } catch (e) { nb.erreur = messageErreur(e, "Le bail n'a pas été créé.") } finally { nb.enCours = false }
}
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Appartements</div>
        <h1 class="page-title">Baux</h1>
        <p class="page-sub">Séjours au mois, échéancier, charges et compteurs</p>
      </div>
      <v-btn v-if="peut" color="primary" variant="flat" size="large" prepend-icon="mdi-file-sign" @click="nouveau">Nouveau bail</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div class="resume mb-5">
      <div class="d-carte resume__bloc"><span class="d-icone d-icone--sm"><v-icon icon="mdi-home-city-outline" size="18" /></span>
        <div><b>{{ totaux.actifs }}</b><small>bail(aux) actif(s)</small></div></div>
      <div class="d-carte resume__bloc"><span class="d-icone d-icone--sm" style="--c: #0d9488"><v-icon icon="mdi-cash-multiple" size="18" /></span>
        <div><b>{{ usd(totaux.loyers) }}</b><small>loyers mensuels</small></div></div>
      <div class="d-carte resume__bloc"><span class="d-icone d-icone--sm" style="--c: #d97706"><v-icon icon="mdi-file-clock-outline" size="18" /></span>
        <div><b>{{ totaux.aFacturer }}</b><small>échéance(s) à facturer</small></div></div>
      <div class="d-carte resume__bloc"><span class="d-icone d-icone--sm" style="--c: #e11d48"><v-icon icon="mdi-cash-clock" size="18" /></span>
        <div><b>{{ usd(totaux.du) }}</b><small>facturé, non réglé</small></div></div>
    </div>

    <div class="d-filtres mb-4">
      <button v-for="f in ([{ v: 'ACTIF', t: 'Actifs' }, { v: 'TOUS', t: 'Tous' }] as const)" :key="f.v" type="button" class="d-filtre" :class="{ 'd-filtre--actif': statut === f.v }"
        style="--c: var(--color-primary)" @click="statut = f.v"><span class="d-filtre__point" />{{ f.t }}</button>
    </div>

    <div v-if="baux.length" class="baux">
      <NuxtLink v-for="b in baux" :key="b.id" :to="`/baux/${b.id}`" class="bail" :class="{ 'bail--clos': b.statut !== 'ACTIF' }">
        <div class="bail__tete">
          <span class="bail__unite">{{ b.uniteNumero }}</span>
          <div class="bail__titres"><strong>{{ b.clientNom }}</strong><small>{{ b.reference }} · {{ b.typeNom }} · {{ b.etablissementNom }}</small></div>
          <span v-if="b.echeancesAFacturer" class="pastille" style="--c: #d97706">{{ b.echeancesAFacturer }} à facturer</span>
          <span v-else-if="b.statut !== 'ACTIF'" class="pastille" style="--c: #64748b">{{ b.statut === 'RESILIE' ? 'Résilié' : 'Terminé' }}</span>
        </div>
        <div class="bail__loyer"><b>{{ usd(b.loyerMensuel) }}</b><span>/ mois{{ Number(b.chargesForfait) ? ` + ${usd(b.chargesForfait)} de charges` : '' }}</span></div>
        <div class="bail__duree">
          <div class="bail__barre"><span :style="{ width: `${progression(b)}%` }" /></div>
          <div class="bail__dates"><span>{{ dateCourte(b.dateDebut) }}</span><span>{{ dateCourte(b.dateFin) }}</span></div>
        </div>
        <div v-if="Number(b.duFacture) || b.prochaineEcheance" class="bail__pied">
          <span v-if="b.prochaineEcheance"><v-icon icon="mdi-calendar-clock" size="14" />Prochaine échéance {{ dateCourte(b.prochaineEcheance) }}</span>
          <span v-if="Number(b.duFacture)" class="bail__du">{{ usd(b.duFacture) }} dû</span>
        </div>
      </NuxtLink>
    </div>
    <div v-else-if="charge" class="d-carte"><div class="d-vide">
      <div class="d-vide__icone"><v-icon icon="mdi-file-sign" size="28" /></div><div class="d-vide__titre">Aucun bail</div>
      <div class="d-vide__texte">Un bail loue un appartement au mois : son loyer est facturé chaque mois dans M-FINAPP, à crédit, avec les charges et les compteurs.</div>
    </div></div>

    <v-dialog v-model="nb.ouvert" max-width="720" scrollable>
      <v-card class="pa-2">
        <v-card-title class="text-h6 font-weight-bold">Nouveau bail</v-card-title>
        <v-card-text>
          <v-alert v-if="nb.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ nb.erreur }}</v-alert>
          <v-autocomplete v-model="nb.uniteId" :items="unites.map(u => ({ value: u.id, title: `${u.numero} — ${u.typeNom}`, subtitle: u.etablissementNom }))" label="Appartement *" prepend-inner-icon="mdi-home-outline">
            <template #item="{ props: p, item }"><v-list-item v-bind="p" :subtitle="item.raw.subtitle" /></template>
          </v-autocomplete>
          <div class="d-flex align-center justify-space-between mb-2"><span class="text-subtitle-2">Locataire</span>
            <v-btn size="small" variant="text" :prepend-icon="nb.nouveau ? 'mdi-magnify' : 'mdi-account-plus-outline'" @click="nb.nouveau = !nb.nouveau">{{ nb.nouveau ? 'Client existant' : 'Nouveau client' }}</v-btn></div>
          <v-autocomplete v-if="!nb.nouveau" v-model="nb.clientId" v-model:search="recherche" no-filter label="Rechercher le client"
            :items="clients.map(c => ({ value: c.id, title: `${c.prenom ? c.prenom + ' ' : ''}${c.nom}`, subtitle: c.telephone ?? '' }))" prepend-inner-icon="mdi-account-search-outline" />
          <v-row v-else dense>
            <v-col cols="12" sm="6"><v-text-field v-model="nb.nc.nom" label="Nom *" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nb.nc.prenom" label="Prénom" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nb.nc.telephone" label="Téléphone" inputmode="tel" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nb.nc.email" label="E-mail" type="email" /></v-col>
          </v-row>
          <v-row dense>
            <v-col cols="6"><v-text-field v-model="nb.dateDebut" type="date" label="Entrée *" /></v-col>
            <v-col cols="6"><v-text-field v-model="nb.dateFin" type="date" label="Sortie prévue *" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model.number="nb.loyerMensuel" type="number" min="0" label="Loyer mensuel (USD HT) *" prefix="$" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model.number="nb.chargesForfait" type="number" min="0" label="Charges au forfait / mois" prefix="$" /></v-col>
          </v-row>
          <v-switch v-model="nb.refacturerCompteurs" color="primary" inset hide-details label="Refacturer l’eau et l’électricité relevées aux compteurs" />
          <v-textarea v-model="nb.conditions" label="Conditions particulières" rows="2" auto-grow class="mt-3" />
          <p class="text-caption text-medium-emphasis mb-0">Le locataire doit être lié à M-FINAPP pour la facturation à crédit (fiche client › Lier à M-FINAPP).
            La caution s’encaisse depuis le séjour, comme pour un hôtel.</p>
        </v-card-text>
        <v-card-actions class="px-6 pb-4"><v-spacer /><v-btn variant="text" @click="nb.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="nb.enCours" :disabled="!nb.uniteId || !nb.loyerMensuel || !nb.dateFin || (nb.nouveau ? !nb.nc.nom.trim() : !nb.clientId)" @click="creer">Créer le bail</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.resume { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.resume__bloc { display: flex; align-items: center; gap: 14px; padding: 16px 18px; }
.resume__bloc b { display: block; font-size: 1.35rem; font-weight: 780; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; }
.resume__bloc small { display: block; font-size: 0.8rem; color: var(--d-ink-soft); }
.baux { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.bail { display: flex; flex-direction: column; gap: 14px; padding: 18px; border-radius: var(--d-r-lg); text-decoration: none; color: inherit;
  background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-1); transition: transform 0.2s, box-shadow 0.2s; }
.bail:hover { transform: translateY(-2px); box-shadow: var(--d-shadow-3); }
.bail--clos { opacity: 0.7; }
.bail__tete { display: flex; align-items: center; gap: 12px; }
.bail__unite { min-width: 50px; height: 50px; padding: 0 8px; border-radius: 15px; display: grid; place-items: center; font-weight: 800; font-size: 1.05rem;
  color: #fff; background: linear-gradient(135deg, #818cf8, #4338ca); font-variant-numeric: tabular-nums; }
.bail__titres { flex: 1; min-width: 0; }
.bail__titres strong { display: block; font-size: 0.98rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bail__titres small { display: block; font-size: 0.76rem; color: var(--d-ink-faint); }
.bail__loyer b { font-size: 1.35rem; font-weight: 780; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; }
.bail__loyer span { font-size: 0.82rem; color: var(--d-ink-soft); margin-left: 4px; }
.bail__barre { height: 6px; border-radius: 999px; background: var(--d-surface-3); overflow: hidden; }
.bail__barre span { display: block; height: 100%; background: linear-gradient(90deg, #818cf8, #4338ca); border-radius: inherit; }
.bail__dates { display: flex; justify-content: space-between; font-size: 0.74rem; color: var(--d-ink-faint); margin-top: 6px; }
.bail__pied { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 0.78rem; color: var(--d-ink-soft); padding-top: 10px; border-top: 1px dashed var(--d-line-strong); }
.bail__pied span { display: inline-flex; align-items: center; gap: 4px; }
.bail__du { font-weight: 750; color: var(--menage-sale); }
@media (max-width: 959px) { .resume { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
