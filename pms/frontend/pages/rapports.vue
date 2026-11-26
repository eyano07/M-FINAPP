<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { CANAUX, jourIso, usd } from '~/utils/libelles'

/**
 * Rapports de l'hébergement : taux d'occupation, prix moyen par nuit (ADR), revenu par unité disponible (RevPAR),
 * chiffre d'affaires par établissement, par canal et par unité, courbe d'occupation jour par jour. Montants en USD HT ;
 * la comptabilité reste celle de M-FINAPP (journal des ventes, analytique 986).
 */
useHead({ title: 'Rapports' })

interface Indicateurs { unites: number, nuitsDisponibles: number, nuitsVendues: number, occupation: number, caNuitees: number, adr: number, revpar: number,
  sejoursCourts: number, caCourts: number, caExtras: number, caTotal: number, sejours: number }
interface Synthese { du: string, au: string, total: Indicateurs, etablissements: { id: number, nom: string, type: string, indicateurs: Indicateurs }[],
  canaux: { canal: string, sejours: number, nuits: number, ca: number, part: number }[],
  unites: { id: number, numero: string, typeNom: string, etablissementNom: string, nuits: number, occupation: number, ca: number }[],
  jours: { jour: string, occupees: number, occupation: number, ca: number }[] }

const api = useApi()
const etablissements = useEtablissementStore()
const maintenant = new Date()
const PERIODES = [
  { cle: 'mois', titre: 'Ce mois', du: () => new Date(maintenant.getFullYear(), maintenant.getMonth(), 1), au: () => new Date(maintenant.getFullYear(), maintenant.getMonth() + 1, 0) },
  { cle: 'precedent', titre: 'Mois dernier', du: () => new Date(maintenant.getFullYear(), maintenant.getMonth() - 1, 1), au: () => new Date(maintenant.getFullYear(), maintenant.getMonth(), 0) },
  { cle: '30j', titre: '30 derniers jours', du: () => new Date(Date.now() - 29 * 86_400_000), au: () => new Date() },
  { cle: 'annee', titre: 'Cette année', du: () => new Date(maintenant.getFullYear(), 0, 1), au: () => new Date(maintenant.getFullYear(), 11, 31) },
]
const periode = ref('mois')
const du = ref(jourIso(PERIODES[0].du()))
const au = ref(jourIso(PERIODES[0].au()))
const previsionnel = ref(true)
const s = ref<Synthese | null>(null)
const chargement = ref(false)
const erreur = ref('')

function choisir(cle: string) {
  periode.value = cle
  const p = PERIODES.find(x => x.cle === cle)
  if (p) { du.value = jourIso(p.du()); au.value = jourIso(p.au()) }
}
async function charger() {
  chargement.value = true
  try {
    s.value = await api<Synthese>('/rapports/synthese', { query: { du: du.value, au: au.value, etablissementId: etablissements.filtre, previsionnel: previsionnel.value } })
    erreur.value = ''
  } catch (e) { erreur.value = messageErreur(e, 'Rapport indisponible.') } finally { chargement.value = false }
}
onMounted(charger)
watch([du, au, previsionnel], charger)

const libCanal = (c: string) => CANAUX.find(x => x.value === c)?.title ?? c
const maxJour = computed(() => Math.max(1, ...(s.value?.jours ?? []).map(j => Number(j.occupation))))
const unitesTriees = computed(() => [...(s.value?.unites ?? [])].sort((a, b) => Number(b.ca) - Number(a.ca)))
const maxUnite = computed(() => Math.max(1, ...unitesTriees.value.map(u => Number(u.ca))))
const pct = (v: number) => `${Number(v).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`

function exporter() {
  if (!s.value) return
  const lignes = [['Unité', 'Type', 'Établissement', 'Nuits vendues', 'Occupation %', 'CA HT USD'],
    ...unitesTriees.value.map(u => [u.numero, u.typeNom, u.etablissementNom, u.nuits, u.occupation, u.ca]),
    [], ['Canal', 'Séjours', 'Nuits', 'CA HT USD', 'Part %'], ...s.value.canaux.map(c => [libCanal(c.canal), c.sejours, c.nuits, c.ca, c.part])]
  const csv = lignes.map(l => l.map(v => `"${String(v ?? '').replace(/"/g, '""')}"`).join(';')).join('\r\n')
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `rapport-hebergement-${du.value}-${au.value}.csv`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Gestion</div>
        <h1 class="page-title">Rapports</h1>
        <p class="page-sub">Occupation, prix moyen et revenu par unité disponible</p>
      </div>
      <v-btn variant="tonal" prepend-icon="mdi-file-delimited-outline" :disabled="!s" @click="exporter">Exporter (CSV)</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <div class="filtres mb-5">
      <div class="d-filtres">
        <button v-for="p in PERIODES" :key="p.cle" type="button" class="d-filtre" :class="{ 'd-filtre--actif': periode === p.cle }" style="--c: var(--color-primary)" @click="choisir(p.cle)">{{ p.titre }}</button>
      </div>
      <div class="filtres__dates">
        <v-text-field v-model="du" type="date" label="Du" density="compact" hide-details @update:model-value="periode = ''" />
        <v-text-field v-model="au" type="date" label="Au" density="compact" hide-details @update:model-value="periode = ''" />
        <v-switch v-model="previsionnel" color="primary" inset hide-details density="compact" label="Réservations à venir comprises" />
      </div>
    </div>

    <v-progress-linear v-if="chargement" indeterminate color="primary" rounded class="mb-4" />
    <template v-if="s">
      <div class="kpis mb-5">
        <div class="d-carte kpi kpi--fort">
          <div class="kpi__libelle">Taux d’occupation</div>
          <div class="kpi__valeur">{{ pct(s.total.occupation) }}</div>
          <div class="kpi__barre"><span :style="{ width: `${Math.min(100, s.total.occupation)}%` }" /></div>
          <div class="kpi__note">{{ s.total.nuitsVendues }} nuits vendues sur {{ s.total.nuitsDisponibles }}</div>
        </div>
        <div class="d-carte kpi">
          <div class="kpi__libelle">Prix moyen par nuit <abbr title="Average Daily Rate">ADR</abbr></div>
          <div class="kpi__valeur">{{ usd(s.total.adr) }}</div>
          <div class="kpi__note">CA des nuitées / nuits vendues</div>
        </div>
        <div class="d-carte kpi">
          <div class="kpi__libelle">Revenu par unité disponible <abbr title="Revenue Per Available Room">RevPAR</abbr></div>
          <div class="kpi__valeur">{{ usd(s.total.revpar) }}</div>
          <div class="kpi__note">CA des nuitées / nuits disponibles</div>
        </div>
        <div class="d-carte kpi">
          <div class="kpi__libelle">Chiffre d’affaires HT</div>
          <div class="kpi__valeur">{{ usd(s.total.caTotal) }}</div>
          <div class="kpi__note">nuitées {{ usd(s.total.caNuitees) }} · courts {{ usd(s.total.caCourts) }} · extras {{ usd(s.total.caExtras) }}</div>
        </div>
      </div>

      <section class="d-carte mb-5">
        <div class="d-section">
          <span class="d-icone d-icone--sm"><v-icon icon="mdi-chart-bar" size="18" /></span>
          <div><div class="d-section__titre">Occupation jour par jour</div><div class="d-section__sous">{{ s.jours.length }} jour(s) · survolez une barre pour le détail</div></div>
        </div>
        <div class="courbe" :style="{ '--n': s.jours.length }">
          <div v-for="j in s.jours" :key="j.jour" class="courbe__col" :title="`${new Date(j.jour).toLocaleDateString('fr-FR')} — ${pct(j.occupation)} (${j.occupees} unité(s)), ${usd(j.ca)}`">
            <span class="courbe__barre" :style="{ height: `${Math.max(2, Number(j.occupation) * 100 / maxJour)}%` }" :class="{ 'courbe__barre--futur': j.jour > jourIso(new Date()) }" />
          </div>
        </div>
        <div class="courbe__axe"><span>{{ new Date(s.du).toLocaleDateString('fr-FR') }}</span><span>{{ new Date(s.au).toLocaleDateString('fr-FR') }}</span></div>
      </section>

      <div class="grille mb-5">
        <section class="d-carte">
          <div class="d-section"><span class="d-icone d-icone--sm" style="--c: #7c3aed"><v-icon icon="mdi-source-branch" size="18" /></span>
            <div><div class="d-section__titre">Par canal</div><div class="d-section__sous">Part du chiffre d’affaires</div></div></div>
          <div class="canaux">
            <div v-for="c in s.canaux" :key="c.canal" class="canal">
              <div class="canal__tete"><strong>{{ libCanal(c.canal) }}</strong><span>{{ usd(c.ca) }}</span></div>
              <div class="canal__barre"><span :style="{ width: `${c.part}%` }" /></div>
              <div class="canal__note">{{ c.sejours }} séjour(s) · {{ c.nuits }} nuit(s) · {{ pct(c.part) }}</div>
            </div>
            <p v-if="!s.canaux.length" class="text-body-2 text-medium-emphasis mb-0">Aucun séjour sur la période.</p>
          </div>
        </section>
        <section class="d-carte">
          <div class="d-section"><span class="d-icone d-icone--sm" style="--c: #0d9488"><v-icon icon="mdi-domain" size="18" /></span>
            <div><div class="d-section__titre">Par établissement</div></div></div>
          <v-table density="comfortable">
            <thead><tr><th>Établissement</th><th class="text-right">Occupation</th><th class="text-right">ADR</th><th class="text-right">RevPAR</th><th class="text-right">CA HT</th></tr></thead>
            <tbody>
              <tr v-for="e in s.etablissements" :key="e.id">
                <td><strong>{{ e.nom }}</strong><div class="text-caption text-medium-emphasis">{{ e.indicateurs.unites }} unité(s)</div></td>
                <td class="text-right">{{ pct(e.indicateurs.occupation) }}</td>
                <td class="text-right">{{ usd(e.indicateurs.adr) }}</td>
                <td class="text-right">{{ usd(e.indicateurs.revpar) }}</td>
                <td class="text-right"><strong>{{ usd(e.indicateurs.caTotal) }}</strong></td>
              </tr>
            </tbody>
          </v-table>
        </section>
      </div>

      <section class="d-carte">
        <div class="d-section"><span class="d-icone d-icone--sm" style="--c: #2563eb"><v-icon icon="mdi-bed-outline" size="18" /></span>
          <div><div class="d-section__titre">Par unité</div><div class="d-section__sous">Classées par chiffre d’affaires</div></div></div>
        <div class="unites">
          <div v-for="u in unitesTriees" :key="u.id" class="unite">
            <span class="unite__num">{{ u.numero }}</span>
            <div class="unite__texte"><strong>{{ u.typeNom }}</strong><small>{{ u.etablissementNom }} · {{ u.nuits }} nuit(s) · {{ pct(u.occupation) }}</small></div>
            <div class="unite__barre"><span :style="{ width: `${Number(u.ca) * 100 / maxUnite}%` }" /></div>
            <b class="unite__ca">{{ usd(u.ca) }}</b>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.filtres { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12px; align-items: center; }
.filtres__dates { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.filtres__dates .v-text-field { width: 170px; flex: none; }
.kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.kpi { padding: 20px; }
.kpi--fort { color: #fff; background: var(--d-grad-hero) !important; border: 0 !important; }
.kpi--fort .kpi__libelle, .kpi--fort .kpi__note { color: rgba(255, 255, 255, 0.78); }
.kpi__libelle { font-size: 0.82rem; font-weight: 600; color: var(--d-ink-soft); }
.kpi__libelle abbr { text-decoration: none; font-size: 0.7rem; font-weight: 700; padding: 1px 6px; border-radius: 6px; margin-left: 4px; background: var(--d-surface-3); }
.kpi--fort abbr { background: rgba(255, 255, 255, 0.15); }
.kpi__valeur { font-size: clamp(1.7rem, 2.6vw, 2.2rem); font-weight: 780; letter-spacing: -0.04em; margin: 10px 0 8px; font-variant-numeric: tabular-nums; }
.kpi__barre { height: 6px; border-radius: 999px; background: rgba(255, 255, 255, 0.18); overflow: hidden; margin-bottom: 8px; }
.kpi__barre span { display: block; height: 100%; border-radius: inherit; background: #fff; }
.kpi__note { font-size: 0.76rem; color: var(--d-ink-faint); }
.courbe { display: grid; grid-template-columns: repeat(var(--n), minmax(0, 1fr)); align-items: end; gap: 3px; height: 180px; padding: 12px 20px 0; }
.courbe__col { height: 100%; display: flex; align-items: flex-end; }
.courbe__barre { width: 100%; border-radius: 6px 6px 2px 2px; background: var(--d-grad); transition: height 0.6s cubic-bezier(0.2, 0.7, 0.2, 1); }
.courbe__barre--futur { background: color-mix(in srgb, var(--color-primary) 30%, transparent); }
.courbe__col:hover .courbe__barre { filter: brightness(1.15); }
.courbe__axe { display: flex; justify-content: space-between; padding: 8px 20px 16px; font-size: 0.74rem; color: var(--d-ink-faint); }
.grille { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 16px; align-items: start; }
.canaux { padding: 6px 20px 18px; display: grid; gap: 14px; }
.canal__tete { display: flex; justify-content: space-between; font-size: 0.88rem; }
.canal__tete span { font-weight: 700; font-variant-numeric: tabular-nums; }
.canal__barre { height: 8px; border-radius: 999px; background: var(--d-surface-3); margin: 6px 0 4px; overflow: hidden; }
.canal__barre span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #a78bfa, #7c3aed); }
.canal__note { font-size: 0.74rem; color: var(--d-ink-faint); }
.unites { padding: 4px 20px 18px; }
.unite { display: grid; grid-template-columns: auto minmax(120px, 0.8fr) minmax(0, 1.6fr) auto; align-items: center; gap: 14px; padding: 9px 0; border-bottom: 1px dashed var(--d-line-strong); }
.unite:last-child { border-bottom: 0; }
.unite__num { min-width: 46px; padding: 6px 8px; border-radius: 10px; text-align: center; font-weight: 800; font-variant-numeric: tabular-nums; background: var(--d-surface-3); }
.unite__texte strong { display: block; font-size: 0.86rem; }
.unite__texte small { display: block; font-size: 0.74rem; color: var(--d-ink-faint); }
.unite__barre { height: 8px; border-radius: 999px; background: var(--d-surface-3); overflow: hidden; }
.unite__barre span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #60a5fa, #2563eb); }
.unite__ca { font-variant-numeric: tabular-nums; min-width: 90px; text-align: right; }
@media (max-width: 1279px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } .grille { grid-template-columns: 1fr; } }
@media (max-width: 599px) { .unite { grid-template-columns: auto minmax(0, 1fr) auto; } .unite__barre { display: none; } }
</style>
