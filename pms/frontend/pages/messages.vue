<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { dateHeure } from '~/utils/libelles'

/**
 * Messages automatiques aux clients, par e-mail (messagerie de M-FINAPP) : confirmation, rappel la veille de l'arrivée,
 * code d'accès à l'arrivée, remerciement après le départ. Un message par séjour et par type ; les clients sans e-mail
 * n'en reçoivent pas. Chaque modèle s'édite à côté de son aperçu (variables remplacées par un exemple) ; une variable
 * s'insère d'un clic à l'endroit du curseur.
 */
useHead({ title: 'Messages aux clients' })

interface Modele { type: string, actif: boolean, sujet: string, corps: string, majLe: string | null, majPar: string | null }
interface Envoi { id: number, reservationId: number, reference: string, type: string, destinataire: string, statut: string, detail: string | null, envoyeLe: string }

const TYPES: Record<string, { titre: string, quand: string, icone: string, c: string }> = {
  CONFIRMATION: { titre: 'Confirmation', quand: 'Dès que la réservation est confirmée', icone: 'mdi-check-decagram-outline', c: '#16a34a' },
  RAPPEL_ARRIVEE: { titre: "Rappel d'arrivée", quand: "La veille de l'arrivée", icone: 'mdi-bell-ring-outline', c: '#2563eb' },
  CODE_ACCES: { titre: "Code d'accès", quand: "À l'arrivée, si un code ou une clé est saisi", icone: 'mdi-key-outline', c: '#d97706' },
  REMERCIEMENT: { titre: 'Remerciement', quand: 'Après le départ', icone: 'mdi-heart-outline', c: '#db2777' },
}
const VARIABLES: { cle: string, libelle: string }[] = [
  { cle: 'client', libelle: 'Client' }, { cle: 'reference', libelle: 'Référence' }, { cle: 'etablissement', libelle: 'Établissement' },
  { cle: 'unite', libelle: 'Unité' }, { cle: 'arrivee', libelle: 'Arrivée' }, { cle: 'depart', libelle: 'Départ' }, { cle: 'code', libelle: 'Code' },
]

const api = useApi()
const session = useSessionStore()
const direction = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))
const modeles = ref<Modele[]>([])
const envois = ref<Envoi[]>([])
const erreur = ref('')
const succes = ref('')
const enregistrement = ref<string | null>(null)
const edition = reactive<Record<string, { sujet: string, corps: string, actif: boolean }>>({})

async function charger() {
  try {
    const [m, e] = await Promise.all([api<Modele[]>('/messages/modeles'), api<Envoi[]>('/messages/envois')])
    modeles.value = m
    envois.value = e
    for (const x of m) edition[x.type] = { sujet: x.sujet, corps: x.corps, actif: x.actif }
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
onMounted(charger)

const modifie = (m: Modele) => !!edition[m.type] && (edition[m.type].sujet !== m.sujet || edition[m.type].corps !== m.corps || edition[m.type].actif !== m.actif)

async function enregistrer(type: string) {
  enregistrement.value = type
  try {
    await api(`/messages/modeles/${type}`, { method: 'PUT', body: edition[type] })
    succes.value = `Modèle « ${TYPES[type].titre} » enregistré.`
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    enregistrement.value = null
  }
}

// ── Insertion d'une variable au curseur ────────────────────────────────────────────────────────────────────
const dernier = reactive<{ type: string | null, champ: 'sujet' | 'corps' }>({ type: null, champ: 'corps' })
function suivre(type: string, champ: 'sujet' | 'corps') { dernier.type = type; dernier.champ = champ }
async function inserer(type: string, cle: string) {
  if (!direction.value || !edition[type]) return
  const champ = dernier.type === type ? dernier.champ : 'corps'
  const el = document.getElementById(`${champ}-${type}`) as HTMLInputElement | HTMLTextAreaElement | null
  const texte = edition[type][champ]
  const jeton = `{${cle}}`
  const debut = el?.selectionStart ?? texte.length
  const fin = el?.selectionEnd ?? texte.length
  edition[type][champ] = texte.slice(0, debut) + jeton + texte.slice(fin)
  await nextTick()
  el?.focus()
  el?.setSelectionRange(debut + jeton.length, debut + jeton.length)
}

// ── Aperçu avec des valeurs d'exemple ──────────────────────────────────────────────────────────────────────
const demain = new Date(Date.now() + 86_400_000)
const apres = new Date(Date.now() + 3 * 86_400_000)
const jour = (d: Date) => d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' })
const EXEMPLE = computed<Record<string, string>>(() => ({
  client: 'Amina Kabila', reference: 'RES-2026-000128', etablissement: useEtablissementStore().courant?.nom ?? 'Hôtel Exemple',
  unite: '204', arrivee: `${jour(demain)} 14:00`, depart: `${jour(apres)} 12:00`, code: '4821',
}))
const echapper = (s: string) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!))
function rendu(texte: string) {
  return echapper(texte ?? '').replace(/\{(\w+)\}/g, (tout, cle) => EXEMPLE.value[cle] != null
    ? `<mark class="variable">${echapper(EXEMPLE.value[cle])}</mark>` : `<mark class="variable variable--inconnue">${tout}</mark>`).replace(/\n/g, '<br>')
}
const expediteur = computed(() => useEtablissementStore().courant?.nom ?? 'Votre établissement')

const envoyes = computed(() => envois.value.filter(e => e.statut === 'ENVOYE').length)
const echecs = computed(() => envois.value.filter(e => e.statut !== 'ENVOYE').length)
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Distribution</div>
        <h1 class="page-title">Messages aux clients</h1>
        <p class="page-sub">E-mails automatiques envoyés par la messagerie de M-FINAPP</p>
      </div>
      <div v-if="envois.length" class="bilan">
        <span class="bilan__bloc"><b>{{ envoyes }}</b>envoyé(s)</span>
        <span v-if="echecs" class="bilan__bloc bilan__bloc--echec"><b>{{ echecs }}</b>échec(s)</span>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <div class="note">
      <span class="d-icone d-icone--sm" style="--c: #2563eb"><v-icon icon="mdi-information-outline" size="18" /></span>
      <span>L’envoi passe par la messagerie réglée dans M-FINAPP (Administration › Messagerie). Cliquez sur une variable pour l’insérer à
        l’endroit du curseur. WhatsApp et SMS viendront quand un fournisseur sera choisi.</span>
    </div>

    <div class="modeles">
      <section v-for="m in modeles" :key="m.type" class="d-carte modele" :class="{ 'modele--inactif': edition[m.type] && !edition[m.type].actif }">
        <header class="modele__tete">
          <span class="d-icone" :style="{ '--c': TYPES[m.type]?.c }"><v-icon :icon="TYPES[m.type]?.icone" size="20" /></span>
          <div class="modele__titres">
            <div class="modele__titre">{{ TYPES[m.type]?.titre ?? m.type }}</div>
            <div class="modele__quand"><v-icon icon="mdi-clock-outline" size="13" />{{ TYPES[m.type]?.quand }}</div>
          </div>
          <div v-if="edition[m.type]" class="modele__etat">
            <span class="modele__etat-texte">{{ edition[m.type].actif ? 'Actif' : 'Désactivé' }}</span>
            <v-switch v-model="edition[m.type].actif" :disabled="!direction" color="primary" hide-details inset density="compact" :aria-label="`Activer ${TYPES[m.type]?.titre}`" />
          </div>
        </header>

        <div v-if="edition[m.type]" class="modele__corps">
          <div class="modele__edition">
            <v-text-field :id="`sujet-${m.type}`" v-model="edition[m.type].sujet" label="Objet" :readonly="!direction" hide-details class="mb-3"
              @focus="suivre(m.type, 'sujet')" @click="suivre(m.type, 'sujet')" />
            <v-textarea :id="`corps-${m.type}`" v-model="edition[m.type].corps" label="Message" rows="5" auto-grow :readonly="!direction" hide-details
              @focus="suivre(m.type, 'corps')" @click="suivre(m.type, 'corps')" />
            <div class="variables">
              <button v-for="v in VARIABLES" :key="v.cle" type="button" class="variables__puce" :disabled="!direction" :title="`Insérer {${v.cle}}`" @click="inserer(m.type, v.cle)">
                <span>{</span>{{ v.libelle }}<span>}</span>
              </button>
            </div>
          </div>

          <div class="apercu" aria-label="Aperçu de l'e-mail">
            <div class="apercu__barre"><i /><i /><i /><span>Aperçu</span></div>
            <div class="apercu__mail">
              <div class="apercu__entete">
                <span class="apercu__avatar">{{ expediteur.slice(0, 1).toUpperCase() }}</span>
                <div class="apercu__de">
                  <strong>{{ expediteur }}</strong>
                  <small>à amina.kabila@exemple.com</small>
                </div>
              </div>
              <div class="apercu__objet" v-html="rendu(edition[m.type].sujet)" />
              <div class="apercu__texte" v-html="rendu(edition[m.type].corps)" />
            </div>
          </div>
        </div>

        <footer class="modele__pied">
          <span class="modele__maj">{{ m.majLe ? `Modifié le ${dateHeure(m.majLe)}${m.majPar ? ` par ${m.majPar}` : ''}` : 'Modèle d’origine' }}</span>
          <v-btn v-if="direction" color="primary" :variant="modifie(m) ? 'flat' : 'tonal'" :disabled="!modifie(m)" :loading="enregistrement === m.type"
            prepend-icon="mdi-content-save-outline" @click="enregistrer(m.type)">Enregistrer</v-btn>
        </footer>
      </section>
    </div>

    <section class="d-carte mt-5">
      <div class="d-section">
        <span class="d-icone d-icone--sm"><v-icon icon="mdi-email-check-outline" size="18" /></span>
        <div><div class="d-section__titre">Derniers envois</div><div class="d-section__sous">Un message par séjour et par type, jamais deux</div></div>
        <v-spacer /><span class="d-compteur">{{ envois.length }}</span>
      </div>
      <v-data-table :items="envois" items-per-page="25" density="comfortable" no-data-text="Aucun message envoyé pour l’instant."
        :headers="[{ title: 'Date', key: 'envoyeLe' }, { title: 'Séjour', key: 'reference' }, { title: 'Message', key: 'type' },
                   { title: 'Destinataire', key: 'destinataire' }, { title: 'Statut', key: 'statut' }]"
        @click:row="(_: unknown, { item }: any) => navigateTo(`/reservations/${item.reservationId}`)">
        <template #[`item.envoyeLe`]="{ item }">{{ dateHeure(item.envoyeLe) }}</template>
        <template #[`item.type`]="{ item }">
          <span class="type-envoi"><v-icon :icon="TYPES[item.type]?.icone" size="16" :style="{ color: TYPES[item.type]?.c }" />{{ TYPES[item.type]?.titre ?? item.type }}</span>
        </template>
        <template #[`item.statut`]="{ item }">
          <span class="pastille" :style="{ '--c': item.statut === 'ENVOYE' ? 'var(--menage-propre)' : 'var(--menage-sale)' }">{{ item.statut === 'ENVOYE' ? 'Envoyé' : 'Échec' }}</span>
          <div v-if="item.detail" class="text-caption text-error">{{ item.detail }}</div>
        </template>
      </v-data-table>
    </section>
  </div>
</template>

<style scoped>
.bilan { display: flex; gap: 10px; }
.bilan__bloc {
  display: inline-flex; align-items: baseline; gap: 6px; padding: 8px 14px; border-radius: 14px; font-size: 0.82rem; color: var(--d-ink-soft);
  background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-1);
}
.bilan__bloc b { font-size: 1.15rem; color: var(--d-ink); font-variant-numeric: tabular-nums; }
.bilan__bloc--echec b { color: var(--menage-sale); }
.note {
  display: flex; align-items: center; gap: 12px; padding: 12px 16px; margin-bottom: 18px; border-radius: 16px; font-size: 0.86rem; color: var(--d-ink-2);
  background: color-mix(in srgb, #2563eb 5%, var(--d-surface)); border: 1px solid color-mix(in srgb, #2563eb 14%, transparent);
}

.modeles { display: grid; gap: 18px; }
.modele { overflow: hidden; }
.modele--inactif .apercu { filter: grayscale(0.7); opacity: 0.6; }
.modele__tete { display: flex; align-items: center; gap: 14px; padding: 18px 20px 4px; }
.modele__titres { flex: 1; min-width: 0; }
.modele__titre { font-weight: 750; font-size: 1.05rem; letter-spacing: -0.02em; }
.modele__quand { display: inline-flex; align-items: center; gap: 5px; font-size: 0.8rem; color: var(--d-ink-faint); }
.modele__etat { display: flex; align-items: center; gap: 4px; }
.modele__etat-texte { font-size: 0.8rem; font-weight: 600; color: var(--d-ink-soft); }
.modele__corps { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 20px; padding: 14px 20px 6px; }
.modele__pied { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 20px 16px; }
.modele__maj { font-size: 0.76rem; color: var(--d-ink-faint); }

.variables { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.variables__puce {
  display: inline-flex; align-items: center; gap: 1px; height: 28px; padding: 0 10px; border-radius: 999px; cursor: pointer;
  font: inherit; font-size: 0.76rem; font-weight: 650; color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 8%, var(--d-surface)); border: 1px solid color-mix(in srgb, var(--color-primary) 18%, transparent);
  transition: transform 0.14s, background 0.14s;
}
.variables__puce span { opacity: 0.45; }
.variables__puce:hover:not(:disabled) { transform: translateY(-1px); background: color-mix(in srgb, var(--color-primary) 14%, var(--d-surface)); }
.variables__puce:disabled { cursor: default; opacity: 0.6; }

.apercu { border-radius: 18px; overflow: hidden; background: var(--d-surface-3); border: 1px solid var(--d-line); align-self: start; }
.apercu__barre { display: flex; align-items: center; gap: 6px; padding: 9px 12px; font-size: 0.7rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--d-ink-faint); }
.apercu__barre i { width: 9px; height: 9px; border-radius: 50%; background: var(--d-line-strong); }
.apercu__barre span { margin-left: 6px; }
.apercu__mail { margin: 0 10px 10px; padding: 16px 18px 18px; border-radius: 14px; background: var(--d-surface); box-shadow: var(--d-shadow-1); }
.apercu__entete { display: flex; align-items: center; gap: 10px; padding-bottom: 12px; border-bottom: 1px solid var(--d-line); }
.apercu__avatar { width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; font-weight: 800; font-size: 0.85rem; color: #fff; background: var(--d-grad); }
.apercu__de strong { display: block; font-size: 0.86rem; }
.apercu__de small { display: block; font-size: 0.74rem; color: var(--d-ink-faint); }
.apercu__objet { font-weight: 750; font-size: 1rem; letter-spacing: -0.015em; margin: 14px 0 8px; }
.apercu__texte { font-size: 0.88rem; line-height: 1.6; color: var(--d-ink-2); }
.apercu :deep(.variable) {
  padding: 0 4px; border-radius: 5px; color: inherit; font-weight: 650;
  background: color-mix(in srgb, var(--color-primary) 14%, transparent);
}
.apercu :deep(.variable--inconnue) { background: color-mix(in srgb, #e11d48 16%, transparent); }
.type-envoi { display: inline-flex; align-items: center; gap: 6px; }

@media (max-width: 959px) {
  .modele__corps { grid-template-columns: 1fr; }
}
</style>
