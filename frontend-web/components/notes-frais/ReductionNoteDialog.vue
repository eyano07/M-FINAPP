<script setup lang="ts">
// Réduction d'une note payée par son émetteur (retour de fonds) : on saisit ce qui a réellement été acheté ou dépensé —
// casiers et bouteilles pour une boisson conditionnée, kg, litres… pour une autre marchandise, montant pour toute autre
// ligne —, la différence est à rendre en espèces à la caisse. Rien ne change avant que le caissier valide la réception de
// l'argent (RetourFondsService.valider). Le calcul affiché ici est indicatif : le serveur refait le sien (RetourFondsCalcul).
import { useDisplay } from 'vuetify'
import type { RetourFonds } from '~/components/notes-frais/DetailRetourFonds.vue'

interface LignePreparation {
  id: number
  description?: string | null
  compteImputation?: string | null
  compteImputationLibelle?: string | null
  achatMarchandise: boolean
  fraisApproche: boolean
  reductible: boolean
  articleId?: number | null
  articleCode?: string | null
  articleLibelle?: string | null
  articleType?: string | null
  unite?: string | null
  entrepotNom?: string | null
  /** Prix unitaire HT (achat de marchandise) ou montant HT de la ligne. */
  montant: number
  quantite?: number | null
  montantTtc: number
  soumisTva: boolean
  tauxTva?: number | null
  echangeConsigne: boolean
  contenanceCasier?: number | null
  stockDisponible?: number | null
}

interface Preparation {
  reductible: boolean
  raison?: string | null
  enAttente?: RetourFonds | null
  devise?: string | null
  montantNote: number
  lignes: LignePreparation[]
}

/** Valeurs saisies pour une ligne (champs numériques : nombre, ou chaîne vide quand le champ est effacé). */
interface Saisie {
  casiers: number | string | null
  bouteilles: number | string | null
  quantite: number | string | null
  montant: number | string | null
}

interface Etat {
  change: boolean
  nouvelleQuantite: number | null
  nouveauMontant: number | null
  rendu: number
  erreur: string | null
}

const props = defineProps<{
  modelValue: boolean
  noteId: number
  reference: string
  devise: string
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'envoyee', retour: RetourFonds): void
}>()

const api = useApi()
const { mobile } = useDisplay()
const rolesStore = useRolesStore()

/** Prévenus à la réception de l'argent (RetourFondsService.NOTIFIES, plus le responsable bar ou cuisine des articles). */
const prevenus = computed(() => [
  rolesStore.libelle('DFIN'), rolesStore.libelle('COMPTABLE'), rolesStore.libelle('DA'), rolesStore.libelle('ADMIN'),
  `${rolesStore.libelle('RESP_BAR')} ou ${rolesStore.libelle('RESP_CUISINE')} selon les articles`,
].join(', '))

const ouvert = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const chargement = ref(false)
const envoi = ref(false)
const erreurChargement = ref('')
const erreurEnvoi = ref('')
const prep = ref<Preparation | null>(null)
const saisies = ref<Record<number, Saisie>>({})
const motif = ref('')

const devise = computed(() => prep.value?.devise || props.devise)

watch(() => props.modelValue, (v) => { if (v) charger() })

async function charger() {
  chargement.value = true
  erreurChargement.value = ''
  erreurEnvoi.value = ''
  motif.value = ''
  prep.value = null
  try {
    const p = await api<Preparation>(`/notes-frais/${props.noteId}/retours-fonds/preparation`)
    const s: Record<number, Saisie> = {}
    for (const l of p.lignes) {
      const q = Number(l.quantite ?? 0)
      if (modeCasiers(l)) {
        const c = Number(l.contenanceCasier)
        s[l.id] = { casiers: Math.floor(q / c), bouteilles: q % c, quantite: null, montant: null }
      } else {
        s[l.id] = { casiers: null, bouteilles: null, quantite: l.achatMarchandise ? q : null, montant: Number(l.montant) }
      }
    }
    saisies.value = s
    prep.value = p
  } catch (e: any) {
    erreurChargement.value = e?.data?.message || 'Impossible de préparer la réduction de cette note.'
  } finally {
    chargement.value = false
  }
}

// ── Calcul (miroir de RetourFondsCalcul.ttc côté serveur) ───────────────────
const arrondi = (v: number, decimales = 2) => {
  const f = 10 ** decimales
  return Math.round((v + Number.EPSILON) * f) / f
}

function ttc(montant: number, quantite: number | null, achat: boolean, soumisTva: boolean, taux?: number | null) {
  const ht = achat && quantite != null ? arrondi(montant * quantite) : montant
  if (!soumisTva || !taux || taux <= 0) return ht
  return arrondi(ht + arrondi(ht * taux / 100))
}

/** Nombre saisi, ou null si le champ est vide ou illisible. */
function nombre(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Boisson conditionnée achetée en bouteilles entières : saisie en casiers + bouteilles. */
function modeCasiers(l: LignePreparation) {
  return l.achatMarchandise && !!l.contenanceCasier && l.contenanceCasier > 0 && Number.isInteger(Number(l.quantite ?? 0))
}

const lignesReductibles = computed(() => (prep.value?.lignes || []).filter(l => l.reductible))

const etats = computed<Record<number, Etat>>(() => {
  const res: Record<number, Etat> = {}
  for (const l of lignesReductibles.value) {
    const s = saisies.value[l.id]
    const etat: Etat = { change: false, nouvelleQuantite: null, nouveauMontant: null, rendu: 0, erreur: null }
    res[l.id] = etat
    if (!s) continue
    const avant = Number(l.montantTtc)
    if (l.achatMarchandise) {
      const ancienne = Number(l.quantite ?? 0)
      let nouvelle: number | null
      if (modeCasiers(l)) {
        const casiers = nombre(s.casiers) ?? 0
        const bouteilles = nombre(s.bouteilles) ?? 0
        if (casiers < 0 || bouteilles < 0 || !Number.isInteger(casiers) || !Number.isInteger(bouteilles)) {
          etat.erreur = 'Saisissez des nombres entiers positifs de casiers et de bouteilles.'
          continue
        }
        nouvelle = casiers * Number(l.contenanceCasier) + bouteilles
      } else {
        nouvelle = nombre(s.quantite)
        if (nouvelle === null) {
          etat.erreur = 'Indiquez la quantité réellement achetée (0 pour tout retirer).'
          continue
        }
        if (nouvelle < 0) {
          etat.erreur = 'Une quantité ne peut pas être négative.'
          continue
        }
        nouvelle = arrondi(nouvelle, 3)
      }
      if (nouvelle > ancienne) {
        etat.erreur = `On ne peut que réduire : la quantité payée est de ${quantiteLigne(l, ancienne)}.`
        continue
      }
      etat.nouvelleQuantite = nouvelle
      etat.rendu = arrondi(avant - ttc(Number(l.montant), nouvelle, true, l.soumisTva, l.tauxTva))
    } else {
      const nouveau = nombre(s.montant)
      if (nouveau === null) {
        etat.erreur = 'Indiquez le montant réellement dépensé (0 pour tout retirer).'
        continue
      }
      if (nouveau < 0) {
        etat.erreur = 'Un montant ne peut pas être négatif.'
        continue
      }
      const arrondiMontant = arrondi(nouveau)
      if (arrondiMontant > Number(l.montant)) {
        etat.erreur = `On ne peut que réduire : le montant payé est de ${fmt(Number(l.montant))} ${devise.value}.`
        continue
      }
      etat.nouveauMontant = arrondiMontant
      etat.rendu = arrondi(avant - ttc(arrondiMontant, null, false, l.soumisTva, l.tauxTva))
    }
    etat.change = etat.rendu > 0
  }

  // Ce qui a déjà été vendu ou consommé ne se rend plus : la quantité retirée d'un article (toutes ses lignes du même
  // entrepôt) ne dépasse pas son stock restant — le serveur refait ce contrôle (RetourFondsService.exigerStockSuffisant).
  const parArticle = new Map<string, { retire: number, stock: number, lignes: LignePreparation[] }>()
  for (const l of lignesReductibles.value) {
    const e = res[l.id]
    if (!l.achatMarchandise || !e || e.erreur || !e.change || l.stockDisponible == null || l.articleId == null) continue
    const cle = `${l.articleId}|${l.entrepotNom || ''}`
    const g = parArticle.get(cle) || { retire: 0, stock: Number(l.stockDisponible), lignes: [] }
    g.retire += Number(l.quantite ?? 0) - (e.nouvelleQuantite ?? 0)
    g.lignes.push(l)
    parArticle.set(cle, g)
  }
  for (const g of parArticle.values()) {
    if (arrondi(g.retire, 3) > g.stock) {
      const l = g.lignes[0]
      for (const x of g.lignes) {
        res[x.id].erreur = `Il n'en reste que ${quantiteLigne(l, g.stock)} en stock${l.entrepotNom ? ` (${l.entrepotNom})` : ''} : `
          + 'ce qui a déjà été vendu ou consommé ne se rend plus.'
      }
    }
  }
  return res
})

const lignesReduites = computed(() => lignesReductibles.value.filter(l => etats.value[l.id]?.change && !etats.value[l.id]?.erreur))
const total = computed(() => arrondi(lignesReduites.value.reduce((s, l) => s + etats.value[l.id].rendu, 0)))
const erreurs = computed(() => lignesReductibles.value.some(l => !!etats.value[l.id]?.erreur))
const peutEnvoyer = computed(() => !!prep.value?.reductible && total.value > 0 && !erreurs.value
  && motif.value.trim().length > 0 && motif.value.length <= 500 && !envoi.value)

async function envoyer() {
  if (!peutEnvoyer.value) return
  envoi.value = true
  erreurEnvoi.value = ''
  try {
    const lignes = lignesReduites.value.map(l => l.achatMarchandise
      ? { ligneId: l.id, nouvelleQuantite: etats.value[l.id].nouvelleQuantite }
      : { ligneId: l.id, nouveauMontant: etats.value[l.id].nouveauMontant })
    const retour = await api<RetourFonds>(`/notes-frais/${props.noteId}/retours-fonds`, {
      method: 'POST',
      body: { motif: motif.value.trim(), lignes },
    })
    emit('envoyee', retour)
    ouvert.value = false
  } catch (e: any) {
    erreurEnvoi.value = e?.data?.message || "La demande n'a pas pu être envoyée."
  } finally {
    envoi.value = false
  }
}

// ── Affichage ─────────────────────────────────────────────────────────────
function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v)
}
function fmtQuantite(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(v)
}

/** Quantité avec son unité : « 4 casiers + 15 bouteilles » pour une boisson conditionnée, « 12,5 kg » sinon. */
function quantiteLigne(l: LignePreparation, q: number) {
  if (modeCasiers(l) && Number.isInteger(q)) {
    const texte = formatCasiers(q, Number(l.contenanceCasier))
    return q >= Number(l.contenanceCasier) ? `${texte} (${fmtQuantite(q)} bouteilles)` : texte
  }
  return `${fmtQuantite(q)}${l.unite ? ` ${l.unite}` : ''}`
}

function titre(l: LignePreparation) {
  return l.articleLibelle || l.description || (l.compteImputation ? `${l.compteImputation} — ${l.compteImputationLibelle || ''}` : 'Ligne')
}

function icone(l: LignePreparation) {
  if (l.fraisApproche) return 'mdi-truck-outline'
  if (l.articleType === 'BOISSON') return 'mdi-bottle-wine-outline'
  if (l.articleType === 'PROVISION') return 'mdi-basket-outline'
  if (l.achatMarchandise) return 'mdi-package-variant'
  return 'mdi-receipt-text-outline'
}

function paye(l: LignePreparation) {
  const ttcTexte = `${fmt(Number(l.montantTtc))} ${devise.value}${l.soumisTva ? ' TTC' : ''}`
  if (l.achatMarchandise) {
    return `${quantiteLigne(l, Number(l.quantite ?? 0))} × ${fmt(Number(l.montant))} = ${ttcTexte}`
  }
  return l.soumisTva ? `${fmt(Number(l.montant))} ${devise.value} HT · ${ttcTexte}` : ttcTexte
}

// Libellé court (il doit tenir sur un téléphone) ; la TVA de la ligne est rappelée sous le champ.
function libelleMontant(l: LignePreparation) {
  return l.soumisTva ? 'Montant réellement dépensé, HT' : 'Montant réellement dépensé'
}

function aideMontant(l: LignePreparation) {
  return l.soumisTva ? `TVA ${fmtQuantite(Number(l.tauxTva || 0))} % en plus` : ''
}
</script>

<template>
  <v-dialog
    v-model="ouvert"
    :fullscreen="mobile"
    :max-width="mobile ? undefined : 760"
    scrollable
    :persistent="envoi"
  >
    <v-card class="rf-card" :rounded="mobile ? 0 : 'xl'">
      <div class="rf-tete">
        <v-icon icon="mdi-cash-refund" size="22" class="mr-2" />
        <div class="rf-tete__texte">
          <span class="rf-tete__titre">Réduire la note {{ reference }}</span>
          <span class="rf-tete__sous-titre">Retour de fonds à la caisse</span>
        </div>
        <v-btn icon="mdi-close" variant="text" size="small" :disabled="envoi" @click="ouvert = false" />
      </div>

      <v-card-text class="rf-corps">
        <div v-if="chargement" class="rf-chargement">
          <v-progress-circular indeterminate color="teal" />
        </div>
        <v-alert v-else-if="erreurChargement" type="error" variant="tonal" rounded="lg">{{ erreurChargement }}</v-alert>

        <template v-else-if="prep">
          <v-alert v-if="!prep.reductible" type="warning" variant="tonal" rounded="lg" class="mb-4">
            {{ prep.raison }}
          </v-alert>
          <p v-else class="rf-intro">
            Indiquez ce qui a <strong>réellement</strong> été acheté ou dépensé. La différence est à remettre
            <strong>en espèces au caissier</strong> : la note, la comptabilité et le stock ne changent qu'au moment où il
            valide la réception de l'argent.
          </p>

          <div class="rf-lignes">
            <div v-for="l in prep.lignes" :key="l.id" class="rf-ligne" :class="{ 'rf-ligne--inactive': !l.reductible }">
              <div class="rf-ligne__titre">
                <v-icon :icon="icone(l)" size="18" class="mr-2" />
                <span>{{ titre(l) }}</span>
                <span v-if="l.entrepotNom" class="rf-ligne__meta">&nbsp;· {{ l.entrepotNom }}</span>
              </div>
              <p class="rf-ligne__paye">Payé : {{ paye(l) }}</p>

              <p v-if="!l.reductible" class="rf-ligne__info">
                Frais d'approche : ils ne se réduisent pas, leur coût est déjà incorporé à la valeur des marchandises en stock.
              </p>
              <template v-else-if="prep.reductible && saisies[l.id]">
                <div class="rf-ligne__saisie">
                  <template v-if="modeCasiers(l)">
                    <v-text-field
                      v-model.number="saisies[l.id].casiers"
                      type="number" min="0" step="1"
                      label="Casiers réellement achetés"
                      :hint="`${l.contenanceCasier} bouteilles par casier`" persistent-hint
                      variant="outlined" density="comfortable"
                    />
                    <v-text-field
                      v-model.number="saisies[l.id].bouteilles"
                      type="number" min="0" step="1"
                      label="Bouteilles en plus"
                      hint="En plus des casiers complets" persistent-hint
                      variant="outlined" density="comfortable"
                    />
                  </template>
                  <v-text-field
                    v-else-if="l.achatMarchandise"
                    v-model.number="saisies[l.id].quantite"
                    type="number" min="0" step="any"
                    :label="`Quantité réellement achetée${l.unite ? ` (${l.unite})` : ''}`"
                    :suffix="l.unite || ''"
                    hide-details="auto"
                    variant="outlined" density="comfortable"
                  />
                  <v-text-field
                    v-else
                    v-model.number="saisies[l.id].montant"
                    type="number" min="0" step="any"
                    :label="libelleMontant(l)"
                    :hint="aideMontant(l)"
                    :persistent-hint="l.soumisTva"
                    :hide-details="l.soumisTva ? false : 'auto'"
                    :suffix="devise"
                    variant="outlined" density="comfortable"
                  />
                </div>
                <p v-if="l.achatMarchandise && l.stockDisponible != null" class="rf-ligne__stock">
                  <v-icon icon="mdi-warehouse" size="14" class="mr-1" />
                  En stock : {{ quantiteLigne(l, Number(l.stockDisponible)) }}<template v-if="l.entrepotNom"> ({{ l.entrepotNom }})</template>
                  <template v-if="l.echangeConsigne"> · les vides des bouteilles retirées reviennent au stock de vides</template>
                </p>
                <p v-if="etats[l.id]?.erreur" class="rf-ligne__erreur">
                  <v-icon icon="mdi-alert-circle-outline" size="14" class="mr-1" />{{ etats[l.id].erreur }}
                </p>
                <p v-else-if="etats[l.id]?.change" class="rf-ligne__rendu">
                  <template v-if="etats[l.id].nouvelleQuantite != null">
                    Nouvelle quantité : {{ quantiteLigne(l, etats[l.id].nouvelleQuantite ?? 0) }} ·
                  </template>
                  À rendre : <strong>{{ fmt(etats[l.id].rendu) }} {{ devise }}</strong>
                </p>
              </template>
            </div>
          </div>

          <template v-if="prep.reductible">
            <v-textarea
              v-model="motif"
              label="Motif de la réduction *"
              placeholder="Ex. : le fournisseur n'a livré que 8 casiers sur 10 ; la facture définitive est moins élevée…"
              rows="2" auto-grow counter="500" maxlength="500"
              variant="outlined" density="comfortable"
              class="mt-4"
            />

            <div class="rf-total">
              <div class="rf-total__ligne">
                <span>Montant de la note</span>
                <span>{{ fmt(Number(prep.montantNote)) }} → {{ fmt(arrondi(Number(prep.montantNote) - total)) }} {{ devise }}</span>
              </div>
              <div class="rf-total__ligne rf-total__ligne--fort">
                <span>À remettre en espèces au caissier</span>
                <strong>{{ fmt(total) }} {{ devise }}</strong>
              </div>
            </div>
            <p class="rf-aide">
              Le caissier valide la réduction quand il reçoit l'argent, ou la refuse avec un motif ; vous êtes prévenu de sa
              décision. Une fois l'argent reçu sont aussi prévenus : {{ prevenus }}.
            </p>
          </template>

          <v-alert v-if="erreurEnvoi" type="error" variant="tonal" rounded="lg" class="mt-3">{{ erreurEnvoi }}</v-alert>
        </template>
      </v-card-text>

      <v-card-actions class="rf-actions">
        <v-btn variant="text" color="grey" :disabled="envoi" @click="ouvert = false">Fermer</v-btn>
        <v-spacer />
        <v-btn
          v-if="prep?.reductible"
          color="teal" variant="flat" rounded="lg"
          prepend-icon="mdi-send"
          :loading="envoi" :disabled="!peutEnvoyer"
          @click="envoyer"
        >
          Envoyer à la caisse
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.rf-card { display: flex; flex-direction: column; }
.rf-tete {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  background: linear-gradient(135deg, #0f766e 0%, #0d9488 100%);
  color: #fff;
}
.rf-tete__texte { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.rf-tete__titre { font-weight: 700; font-size: 1rem; overflow-wrap: anywhere; }
.rf-tete__sous-titre { font-size: 0.78rem; opacity: 0.85; }
.rf-tete :deep(.v-btn) { color: #fff; }
.rf-corps { padding: 18px 20px !important; }
.rf-chargement { display: flex; justify-content: center; padding: 32px 0; }
.rf-intro { margin: 0 0 14px; font-size: 0.875rem; color: #374151; }
.rf-lignes { display: flex; flex-direction: column; gap: 10px; }
.rf-ligne { border: 1px solid #e5e7eb; border-radius: 10px; padding: 12px 14px; }
.rf-ligne--inactive { background: #f9fafb; }
.rf-ligne__titre { display: flex; align-items: center; font-weight: 600; font-size: 0.9rem; color: #111827; overflow-wrap: anywhere; }
.rf-ligne__meta { font-weight: 400; color: #6b7280; }
.rf-ligne__paye { margin: 4px 0 10px; font-size: 0.8rem; color: #6b7280; }
.rf-ligne__info { margin: 0; font-size: 0.8rem; color: #6b7280; font-style: italic; }
.rf-ligne__saisie { display: flex; gap: 10px; flex-wrap: wrap; }
.rf-ligne__saisie > * { flex: 1 1 200px; }
.rf-ligne__stock { margin: 6px 0 0; font-size: 0.78rem; color: #6b7280; display: flex; align-items: center; flex-wrap: wrap; }
.rf-ligne__erreur { margin: 6px 0 0; font-size: 0.8rem; color: #b91c1c; display: flex; align-items: flex-start; }
.rf-ligne__rendu { margin: 6px 0 0; font-size: 0.82rem; color: #0f766e; }
.rf-total { margin-top: 6px; border-radius: 10px; background: #f0fdfa; border: 1px solid #99f6e4; padding: 10px 14px; }
.rf-total__ligne { display: flex; justify-content: space-between; gap: 12px; font-size: 0.85rem; color: #374151; flex-wrap: wrap; }
.rf-total__ligne--fort { margin-top: 4px; font-size: 0.95rem; color: #0f766e; }
.rf-aide { margin: 10px 0 0; font-size: 0.78rem; color: #6b7280; }
.rf-actions { padding: 12px 20px 16px !important; border-top: 1px solid #f3f4f6; }
</style>
