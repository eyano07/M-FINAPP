<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { MENAGE, dateHeure } from '~/utils/libelles'

/**
 * Ménage (design 2027) : état de chaque unité en temps réel et tâches créées aux départs. Pensé pour la tablette et le
 * téléphone : grandes tuiles, l'action suivante en un geste (Commencer → Terminé → Valider), les autres dans un menu.
 */
useHead({ title: 'Ménage' })

interface Etat { id: number, etablissementNom: string, numero: string, typeNom: string, etage: string | null, etatMenage: string,
  horsService: boolean, motifHorsService: string | null, occupee: boolean, clientActuel: string | null, departPrevu: string | null, arriveePrevue: string | null }
interface Tache { id: number, uniteId: number, uniteNumero: string, etablissementNom: string, type: string, statut: string, priorite: string,
  assigneNom: string | null, notes: string | null, creeLe: string, termineLe: string | null }

const api = useApi()
const session = useSessionStore()
const etats = ref<Etat[]>([])
const taches = ref<Tache[]>([])
const filtre = ref<string>('TOUS')
const erreur = ref('')
const charge = ref(false)
const enCours = ref<number | null>(null)
const ecrit = computed(() => session.a('GOUVERNANTE', 'RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))

async function charger() {
  try {
    const query = { etablissementId: useEtablissementStore().filtre }
    const [e, t] = await Promise.all([api<Etat[]>('/menage/unites', { query }), api<Tache[]>('/menage/taches', { query })])
    etats.value = e
    taches.value = t
    erreur.value = ''
  } catch (err) {
    erreur.value = messageErreur(err)
  } finally {
    charge.value = true
  }
}
let minuterie: ReturnType<typeof setInterval> | null = null
onMounted(() => { charger(); minuterie = setInterval(charger, 15_000) })
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

const FILTRES = computed(() => [
  { cle: 'TOUS', libelle: 'Toutes', c: 'var(--d-ink-soft)', nb: etats.value.length },
  ...Object.entries(MENAGE).map(([cle, m]) => ({ cle, libelle: m.libelle, c: m.couleur, nb: etats.value.filter(e => !e.horsService && e.etatMenage === cle).length })),
  { cle: 'HS', libelle: 'Hors service', c: 'var(--d-hs)', nb: etats.value.filter(e => e.horsService).length },
])
const etatsFiltres = computed(() => etats.value.filter(e => filtre.value === 'TOUS' || (filtre.value === 'HS' ? e.horsService : !e.horsService && e.etatMenage === filtre.value)))
const tachesOuvertes = computed(() => taches.value.filter(t => t.statut !== 'TERMINEE'))
const tachesFaites = computed(() => taches.value.filter(t => t.statut === 'TERMINEE'))
const avancement = computed(() => {
  const total = etats.value.filter(e => !e.horsService).length
  return total ? Math.round(etats.value.filter(e => !e.horsService && e.etatMenage === 'PROPRE').length * 100 / total) : 0
})

async function agir(id: number, f: () => Promise<unknown>) {
  enCours.value = id
  try { await f(); await charger() } catch (e) { erreur.value = messageErreur(e) } finally { enCours.value = null }
}
const changer = (u: Etat, etat: string) => agir(u.id, () => api(`/menage/unites/${u.id}/etat`, { method: 'PUT', body: { etat } }))
const commencer = (t: Tache) => agir(-t.id, () => api(`/menage/taches/${t.id}/commencer`, { method: 'POST' }))
const terminer = (t: Tache) => agir(-t.id, () => api(`/menage/taches/${t.id}/terminer`, { method: 'POST' }))

/** Action suivante d'une unité selon son état. */
const SUIVANTE: Record<string, { vers: string, libelle: string, icone: string }> = {
  A_NETTOYER: { vers: 'EN_COURS', libelle: 'Commencer', icone: 'mdi-play' },
  EN_COURS: { vers: 'PROPRE', libelle: 'Terminé', icone: 'mdi-check' },
  A_INSPECTER: { vers: 'PROPRE', libelle: 'Valider', icone: 'mdi-check-decagram' },
}
const TYPES_TACHE: Record<string, string> = { DEPART: 'Après départ', RECOUCHE: 'Recouche', INSPECTION: 'Inspection', AUTRE: 'Tâche' }
const couleur = (u: Etat) => u.horsService ? 'var(--d-hs)' : MENAGE[u.etatMenage]?.couleur
const heure = (iso: string | null) => iso ? new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) : ''
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Opérations</div>
        <h1 class="page-title">Ménage</h1>
        <p class="page-sub">État des unités en temps réel et tâches de nettoyage</p>
      </div>
      <div class="entete-droite">
        <span class="d-direct">En direct</span>
        <div v-if="etats.length" class="avancement" :title="`${avancement} % des unités en service sont prêtes`">
          <div class="avancement__barre"><span :style="{ width: `${avancement}%` }" /></div>
          <span class="avancement__texte"><b>{{ avancement }} %</b> prêtes</span>
        </div>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div v-if="etats.length" class="d-filtres mb-5">
      <button v-for="f in FILTRES" v-show="f.cle === 'TOUS' || f.nb" :key="f.cle" type="button" class="d-filtre" :class="{ 'd-filtre--actif': filtre === f.cle }"
        :style="{ '--c': f.c }" @click="filtre = filtre === f.cle && f.cle !== 'TOUS' ? 'TOUS' : f.cle">
        <span class="d-filtre__point" />{{ f.libelle }}<span class="d-filtre__nb">{{ f.nb }}</span>
      </button>
    </div>

    <section v-if="tachesOuvertes.length || tachesFaites.length" class="d-carte mb-5">
      <div class="d-section">
        <span class="d-icone d-icone--sm" style="--c: var(--menage-sale)"><v-icon icon="mdi-clipboard-check-outline" size="18" /></span>
        <div><div class="d-section__titre">Tâches</div><div class="d-section__sous">{{ tachesOuvertes.length }} à faire · {{ tachesFaites.length }} terminée(s)</div></div>
      </div>
      <div class="taches">
        <div v-for="t in [...tachesOuvertes, ...tachesFaites]" :key="t.id" class="tache" :class="{ 'tache--faite': t.statut === 'TERMINEE', 'tache--urgente': t.priorite === 'HAUTE' && t.statut !== 'TERMINEE' }">
          <span class="tache__unite">{{ t.uniteNumero }}</span>
          <div class="tache__texte">
            <div class="tache__titre">{{ TYPES_TACHE[t.type] ?? 'Tâche' }}
              <span v-if="t.priorite === 'HAUTE' && t.statut !== 'TERMINEE'" class="tache__urgent"><v-icon icon="mdi-flash" size="13" />arrivée aujourd’hui</span>
              <span v-if="t.statut === 'EN_COURS'" class="pastille" style="--c: var(--menage-encours)">En cours</span>
            </div>
            <div class="tache__detail">{{ [t.notes, t.assigneNom, t.statut === 'TERMINEE' ? `terminée à ${heure(t.termineLe)}` : `créée le ${dateHeure(t.creeLe)}`].filter(Boolean).join(' · ') }}</div>
          </div>
          <div v-if="ecrit && t.statut !== 'TERMINEE'" class="tache__actions">
            <v-btn v-if="t.statut === 'A_FAIRE'" variant="tonal" :loading="enCours === -t.id" prepend-icon="mdi-play" @click="commencer(t)">Commencer</v-btn>
            <v-btn color="primary" variant="flat" :loading="enCours === -t.id" prepend-icon="mdi-check" @click="terminer(t)">Terminé</v-btn>
          </div>
          <v-icon v-else-if="t.statut === 'TERMINEE'" icon="mdi-check-circle" color="success" />
        </div>
      </div>
    </section>

    <div v-if="etatsFiltres.length" class="unites">
      <article v-for="u in etatsFiltres" :key="u.id" class="unite" :class="{ 'unite--hs': u.horsService }" :style="{ '--c': couleur(u) }">
        <div class="unite__tete">
          <div>
            <div class="unite__numero">{{ u.numero }}</div>
            <div class="unite__type">{{ u.typeNom }}<template v-if="u.etage"> · étage {{ u.etage }}</template></div>
          </div>
          <span class="pastille" :style="{ '--c': couleur(u) }">{{ u.horsService ? 'Hors service' : MENAGE[u.etatMenage]?.libelle }}</span>
        </div>
        <div class="unite__statut">
          <template v-if="u.horsService"><v-icon icon="mdi-wrench-outline" size="16" /><span>{{ u.motifHorsService || 'En maintenance' }}</span></template>
          <template v-else-if="u.occupee"><v-icon icon="mdi-account" size="16" /><span>{{ u.clientActuel }} · départ {{ dateHeure(u.departPrevu) }}</span></template>
          <template v-else-if="u.arriveePrevue"><v-icon icon="mdi-login-variant" size="16" /><span>Arrivée {{ dateHeure(u.arriveePrevue) }}</span></template>
          <template v-else><v-icon icon="mdi-door-closed" size="16" /><span>Libre</span></template>
        </div>
        <div v-if="ecrit && !u.horsService" class="unite__actions">
          <v-btn v-if="SUIVANTE[u.etatMenage]" class="unite__suivante" variant="flat" :loading="enCours === u.id" :prepend-icon="SUIVANTE[u.etatMenage].icone"
            @click="changer(u, SUIVANTE[u.etatMenage].vers)">{{ SUIVANTE[u.etatMenage].libelle }}</v-btn>
          <span v-else class="unite__prete"><v-icon icon="mdi-check-circle-outline" size="16" />Prête à accueillir</span>
          <v-menu location="bottom end">
            <template #activator="{ props }">
              <v-btn icon="mdi-dots-horizontal" variant="text" size="small" v-bind="props" aria-label="Changer l'état" />
            </template>
            <v-list density="compact" min-width="200">
              <v-list-subheader>Passer à…</v-list-subheader>
              <v-list-item v-for="(m, cle) in MENAGE" v-show="cle !== u.etatMenage" :key="cle" :prepend-icon="m.icone" :title="m.libelle"
                @click="changer(u, cle as string)" />
            </v-list>
          </v-menu>
        </div>
      </article>
    </div>

    <div v-else-if="charge" class="d-carte">
      <div class="d-vide">
        <div class="d-vide__icone"><v-icon :icon="etats.length ? 'mdi-filter-variant-remove' : 'mdi-broom'" size="28" /></div>
        <div class="d-vide__titre">{{ etats.length ? 'Aucune unité dans cet état' : 'Aucune unité à suivre pour l’instant' }}</div>
        <div class="d-vide__texte">{{ etats.length ? 'Choisissez un autre filtre ou revenez à « Toutes ».' : 'Les unités apparaissent ici dès qu’elles sont créées dans Établissements et tarifs ; chaque départ crée sa tâche de nettoyage.' }}</div>
        <v-btn v-if="etats.length" variant="tonal" class="mt-3" @click="filtre = 'TOUS'">Voir toutes les unités</v-btn>
      </div>
    </div>
  </div>
</template>

<style scoped>
.entete-droite { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
.avancement { display: flex; align-items: center; gap: 10px; }
.avancement__barre { width: 140px; height: 8px; border-radius: 999px; background: var(--d-surface-3); overflow: hidden; }
.avancement__barre span { display: block; height: 100%; border-radius: inherit; background: var(--d-grad); transition: width 0.8s cubic-bezier(0.2, 0.7, 0.2, 1); }
.avancement__texte { font-size: 0.82rem; color: var(--d-ink-soft); font-variant-numeric: tabular-nums; }
.avancement__texte b { color: var(--d-ink); }

.taches { padding: 6px 12px 14px; display: grid; gap: 4px; }
.tache { display: flex; align-items: center; gap: 14px; padding: 10px; border-radius: 16px; transition: background 0.16s; }
.tache:hover { background: var(--d-surface-2); }
.tache--urgente { background: color-mix(in srgb, var(--menage-sale) 6%, transparent); }
.tache--faite { opacity: 0.6; }
.tache__unite {
  min-width: 52px; height: 52px; padding: 0 8px; border-radius: 15px; display: grid; place-items: center; flex: none;
  font-weight: 800; font-size: 1.05rem; letter-spacing: -0.03em; font-variant-numeric: tabular-nums;
  color: var(--d-ink); background: var(--d-surface-3);
}
.tache--urgente .tache__unite { color: #fff; background: linear-gradient(135deg, #fb7185, #e11d48); }
.tache__texte { flex: 1; min-width: 0; }
.tache__titre { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-weight: 700; font-size: 0.95rem; }
.tache__urgent { display: inline-flex; align-items: center; gap: 2px; font-size: 0.72rem; font-weight: 700; color: var(--menage-sale); }
.tache__detail { font-size: 0.8rem; color: var(--d-ink-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tache__actions { display: flex; gap: 8px; flex: none; }

.unites { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; }
.unite {
  position: relative; display: flex; flex-direction: column; gap: 12px; padding: 18px 18px 14px; border-radius: var(--d-r-lg); overflow: hidden;
  background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-1); transition: box-shadow 0.2s, transform 0.2s;
}
.unite::before {
  content: ''; position: absolute; inset: 0 0 auto; height: 90px; pointer-events: none;
  background: linear-gradient(180deg, color-mix(in srgb, var(--c) 10%, transparent), transparent);
}
.unite:hover { box-shadow: var(--d-shadow-2); transform: translateY(-2px); }
.unite--hs { background: repeating-linear-gradient(45deg, var(--d-surface) 0 10px, color-mix(in srgb, var(--d-hs) 6%, var(--d-surface)) 10px 20px); }
.unite__tete { position: relative; display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.unite__numero { font-size: 1.9rem; font-weight: 800; letter-spacing: -0.05em; line-height: 1; font-variant-numeric: tabular-nums; }
.unite__type { font-size: 0.78rem; color: var(--d-ink-faint); margin-top: 4px; }
.unite__statut { position: relative; display: flex; align-items: center; gap: 8px; font-size: 0.86rem; color: var(--d-ink-2); min-height: 22px; }
.unite__statut span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.unite__statut .v-icon { color: var(--d-ink-faint); }
.unite__actions { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 10px; border-top: 1px dashed var(--d-line-strong); }
.unite__suivante { flex: 1; color: #fff !important; background: color-mix(in srgb, var(--c) 92%, #000) !important; }
.unite__prete { display: inline-flex; align-items: center; gap: 6px; font-size: 0.82rem; font-weight: 600; color: var(--menage-propre); }

@media (max-width: 599px) {
  .tache { flex-wrap: wrap; }
  .tache__actions { width: 100%; }
  .tache__actions .v-btn { flex: 1; }
  .unites { grid-template-columns: 1fr; }
}
</style>
