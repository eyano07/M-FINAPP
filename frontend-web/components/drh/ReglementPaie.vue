<script setup lang="ts">
/**
 * Règlement de la paie d'un mois : note de frais de paie (salaires nets) puis une note par impôt ou
 * cotisation (IPR, CNSS, INPP, ONEM). Les notes suivent le circuit normal (DFIN, DA, trésorerie) ; le
 * paiement de la note de paie écrit la constatation de la paie (SYSCOHADA) — voir PaieNoteService.
 */
interface NoteResume {
  id: number
  reference: string
  statut: string
  montant: number
  devise: string | null
  organisme: string | null
}
interface Versement {
  organisme: 'IPR' | 'CNSS' | 'INPP' | 'ONEM'
  libelle: string
  beneficiaire: string
  compte: string
  montant: number
  note: NoteResume | null
}
interface Reglement {
  mois: number
  annee: number
  bulletinsValides: number
  bulletinsBrouillon: number
  cloture: boolean
  bulletinsEligibles: number
  totalNet: number
  notePaie: NoteResume | null
  paiePayee: boolean
  versements: Versement[]
  notesAnnulees: NoteResume[]
}

const props = defineProps<{ mois: number, annee: number, canWrite: boolean }>()
const emit = defineEmits<{ (e: 'change'): void }>()

const api = useApi()
const etat = ref<Reglement | null>(null)
const chargement = ref(false)
const action = ref('')
const erreur = ref('')
const succes = ref('')

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    etat.value = await api<Reglement>('/drh/bulletins/reglement', { params: { mois: props.mois, annee: props.annee } })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le règlement de la paie.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)
watch(() => [props.mois, props.annee], charger)
defineExpose({ charger })

async function executer(cle: string, appel: () => Promise<Reglement | unknown>, message: string) {
  action.value = cle
  erreur.value = ''
  succes.value = ''
  try {
    const r = await appel()
    if (r && typeof r === 'object' && 'versements' in (r as any)) etat.value = r as Reglement
    else await charger()
    succes.value = message
    emit('change')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Action impossible.')
  } finally {
    action.value = ''
  }
}

const params = () => ({ mois: props.mois, annee: props.annee })

function creerNotePaie() {
  if (!etat.value) return
  if (!confirm(`Créer la note de paie de ${periode.value} pour ${etat.value.bulletinsEligibles} agent(s), `
    + `total net ${fmtUsd(etat.value.totalNet)} ? Elle sera soumise au DFIN.`)) return
  executer('paie', () => api('/drh/bulletins/reglement/note-paie', { method: 'POST', params: params() }),
    'Note de paie créée et soumise au DFIN. Son paiement écrira la constatation de la paie.')
}

function creerNoteImpot(v: Versement) {
  if (!confirm(`Créer la note « ${v.libelle} » de ${fmtUsd(v.montant)} à verser à ${v.beneficiaire} ?`)) return
  executer(v.organisme, () => api(`/drh/bulletins/reglement/notes-impots/${v.organisme}`, { method: 'POST', params: params() }),
    `Note ${v.organisme} créée et soumise au DFIN.`)
}

function annulerNote(n: NoteResume) {
  const motif = prompt(`Annuler la note ${n.reference} ? Indiquez le motif :`)
  if (motif === null) return
  executer(`annuler-${n.id}`, () => api(`/drh/bulletins/reglement/notes/${n.id}/annuler`, { method: 'POST', body: { commentaire: motif || null } }),
    `Note ${n.reference} annulée.`)
}

function rouvrir() {
  if (!confirm(`Rouvrir la paie de ${periode.value} ? Les bulletins pourront de nouveau être modifiés.`)) return
  executer('rouvrir', () => api('/drh/bulletins/rouvrir', { method: 'POST', params: params() }),
    'Paie rouverte : les bulletins sont de nouveau modifiables.')
}

const MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']
const periode = computed(() => `${MOIS[props.mois - 1]} ${props.annee}`)
const fmtUsd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0)
const annulable = (n: NoteResume) => ['SOUMISE', 'VERIFIEE_DFIN', 'VALIDEE_DA', 'REJETEE_DA'].includes(n.statut)

/** Étape en cours, pour guider le DRH. */
const etape = computed(() => {
  const e = etat.value
  if (!e) return ''
  if (!e.bulletinsValides && !e.bulletinsBrouillon) return 'Aucun bulletin pour ce mois.'
  if (e.bulletinsBrouillon) return `${e.bulletinsBrouillon} bulletin(s) encore en brouillon : validez-les ou annulez-les.`
  if (!e.cloture) return 'Clôturez la paie du mois pour pouvoir créer la note de paie.'
  if (!e.notePaie) return e.bulletinsEligibles ? 'Créez la note de paie du mois.' : 'Ce mois a été comptabilisé avant la mise en place des notes de paie.'
  if (!e.paiePayee) return 'Note de paie en circuit : vérification DFIN, approbation DA, puis paiement par la trésorerie.'
  return 'Paie payée et constatée en comptabilité : créez les notes de versement fiscal.'
})
</script>

<template>
  <v-card class="classroom-card pa-4 mb-4 rp">
    <div class="rp-tete">
      <div>
        <h2 class="rp-titre">Règlement de la paie — {{ periode }}</h2>
        <p class="rp-etape">{{ etape }}</p>
      </div>
      <v-btn v-if="canWrite && etat?.cloture && !etat?.notePaie" variant="text" size="small" prepend-icon="mdi-lock-open-variant-outline"
        :loading="action === 'rouvrir'" @click="rouvrir">
        Rouvrir la paie
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" density="compact" class="mb-3" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-skeleton-loader v-if="chargement && !etat" type="list-item-two-line, list-item-two-line" />

    <template v-else-if="etat">
      <!-- Note de paie -->
      <div class="rp-ligne rp-ligne--paie">
        <div class="rp-ligne__texte">
          <span class="rp-ligne__titre">Salaires nets</span>
          <span class="rp-ligne__sous">
            {{ etat.bulletinsEligibles }} agent(s) · débit 4221.1 · le paiement écrit aussi la constatation de la paie
          </span>
        </div>
        <span class="rp-montant">{{ fmtUsd(etat.notePaie?.montant ?? etat.totalNet) }}</span>
        <div class="rp-ligne__etat">
          <template v-if="etat.notePaie">
            <span class="rp-ref">{{ etat.notePaie.reference }}</span>
            <span class="chip-soft" :style="{ background: statutNoteMeta(etat.notePaie.statut).bg, color: statutNoteMeta(etat.notePaie.statut).text }">
              {{ statutNoteMeta(etat.notePaie.statut).label }}
            </span>
            <v-btn v-if="canWrite && annulable(etat.notePaie)" size="small" variant="text" color="error" icon="mdi-close-circle-outline"
              :title="`Annuler la note ${etat.notePaie.reference}`" :aria-label="`Annuler la note ${etat.notePaie.reference}`"
              :loading="action === `annuler-${etat.notePaie.id}`" @click="annulerNote(etat.notePaie)" />
          </template>
          <v-btn v-else-if="canWrite" color="primary" size="small" rounded="lg" prepend-icon="mdi-file-document-plus-outline"
            :disabled="!etat.cloture || !etat.bulletinsEligibles || etat.bulletinsBrouillon > 0"
            :loading="action === 'paie'" @click="creerNotePaie">
            Créer la note de paie
          </v-btn>
        </div>
      </div>

      <!-- Notes fiscales -->
      <p class="rp-section">Versements fiscaux et sociaux — une note par organisme</p>
      <div v-for="v in etat.versements" :key="v.organisme" class="rp-ligne">
        <div class="rp-ligne__texte">
          <span class="rp-ligne__titre">{{ v.libelle }}</span>
          <span class="rp-ligne__sous">{{ v.beneficiaire }} · débit {{ v.compte }}</span>
        </div>
        <span class="rp-montant">{{ fmtUsd(v.note?.montant ?? v.montant) }}</span>
        <div class="rp-ligne__etat">
          <template v-if="v.note">
            <span class="rp-ref">{{ v.note.reference }}</span>
            <span class="chip-soft" :style="{ background: statutNoteMeta(v.note.statut).bg, color: statutNoteMeta(v.note.statut).text }">
              {{ statutNoteMeta(v.note.statut).label }}
            </span>
            <v-btn v-if="canWrite && annulable(v.note)" size="small" variant="text" color="error" icon="mdi-close-circle-outline"
              :title="`Annuler la note ${v.note.reference}`" :aria-label="`Annuler la note ${v.note.reference}`"
              :loading="action === `annuler-${v.note.id}`" @click="annulerNote(v.note)" />
          </template>
          <v-btn v-else-if="canWrite" variant="tonal" color="primary" size="small" rounded="lg" prepend-icon="mdi-bank-transfer-out"
            :disabled="!etat.paiePayee || !(v.montant > 0)" :loading="action === v.organisme"
            :title="etat.paiePayee ? '' : 'Disponible une fois la note de paie payée'" @click="creerNoteImpot(v)">
            Créer la note
          </v-btn>
        </div>
      </div>

      <p v-if="etat.notesAnnulees.length" class="rp-annulees">
        Notes annulées : {{ etat.notesAnnulees.map(n => n.reference).join(', ') }}
      </p>
    </template>
  </v-card>
</template>

<style scoped>
.rp-tete { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.rp-titre { font-size: 1rem; font-weight: 700; margin: 0; }
.rp-etape { font-size: 0.82rem; color: #4b5563; margin: 2px 0 0; }
.rp-section { font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; color: #4b5563; margin: 16px 0 6px; }
.rp-ligne { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(220px, auto); gap: 12px; align-items: center; padding: 10px 12px; border: 1px solid #eef0f3; border-radius: 10px; margin-top: 6px; }
.rp-ligne--paie { background: var(--color-primary-lighter); border-color: var(--color-primary-mid); }
.rp-ligne__texte { display: flex; flex-direction: column; min-width: 0; }
.rp-ligne__titre { font-weight: 600; font-size: 0.9rem; }
.rp-ligne__sous { font-size: 0.75rem; color: #4b5563; }
.rp-montant { font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.rp-ligne__etat { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.rp-ref { font-family: ui-monospace, monospace; font-size: 0.8rem; white-space: nowrap; }
.rp-annulees { font-size: 0.75rem; color: #4b5563; margin: 10px 0 0; }
@media (max-width: 700px) {
  .rp-ligne { grid-template-columns: minmax(0, 1fr) auto; }
  .rp-ligne__etat { grid-column: 1 / -1; justify-content: flex-start; }
}
</style>
