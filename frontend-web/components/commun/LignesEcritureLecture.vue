<script setup lang="ts">
/**
 * Lignes d'une pièce comptable en lecture seule (compte, libellé, taux, débit, crédit).
 * PC : tableau compact. Téléphone et tablette (< 960 px) : une carte par ligne — le compte sur toute la largeur, le libellé dessous,
 * puis Débit et Crédit côte à côte — pour ne rien couper ni faire défiler la page de côté.
 */
export interface LigneEcritureLecture {
  compteNumero: string
  compteLibelle: string
  libelle?: string | null
  debit: number
  credit: number
  tauxApplique?: number | null
}

const props = withDefaults(defineProps<{
  lignes: LigneEcritureLecture[]
  /** Formate un montant dans la devise du journal. */
  fmt: (montant: number) => string
  /** Colonne « Taux » (livre-journal : lignes en devises converties en USD). */
  fmtTaux?: ((taux: number) => string) | null
  /** Unité ajoutée aux en-têtes Débit / Crédit, ex. « USD ». */
  unite?: string
}>(), { fmtTaux: null, unite: '' })

const suffixe = computed(() => (props.unite ? ` (${props.unite})` : ''))
</script>

<template>
  <div class="lel">
    <v-table density="compact" class="lel__tableau">
      <thead>
        <tr>
          <th>Compte</th>
          <th>Libellé</th>
          <th v-if="fmtTaux" class="text-right">Taux</th>
          <th class="text-right">Débit{{ suffixe }}</th>
          <th class="text-right">Crédit{{ suffixe }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(l, i) in lignes" :key="i">
          <td>
            <span class="font-weight-medium">{{ l.compteNumero }}</span>
            <span class="text-medium-emphasis"> — {{ l.compteLibelle }}</span>
          </td>
          <td>{{ l.libelle }}</td>
          <td v-if="fmtTaux" class="text-right text-medium-emphasis">{{ l.tauxApplique != null ? fmtTaux(l.tauxApplique) : '' }}</td>
          <td class="text-right">{{ l.debit ? fmt(l.debit) : '—' }}</td>
          <td class="text-right">{{ l.credit ? fmt(l.credit) : '—' }}</td>
        </tr>
      </tbody>
    </v-table>

    <ul class="lel__cartes">
      <li v-for="(l, i) in lignes" :key="i" class="lel__carte">
        <div class="lel__compte">
          <span class="lel__numero">{{ l.compteNumero }}</span>
          <span class="lel__compte-libelle">{{ l.compteLibelle }}</span>
        </div>
        <div v-if="l.libelle" class="lel__libelle">{{ l.libelle }}</div>
        <div class="lel__montants">
          <div class="lel__montant" :class="{ 'lel__montant--vide': !l.debit }">
            <span class="lel__etiquette">Débit{{ suffixe }}</span>
            <span class="lel__valeur">{{ l.debit ? fmt(l.debit) : '—' }}</span>
          </div>
          <div class="lel__montant" :class="{ 'lel__montant--vide': !l.credit }">
            <span class="lel__etiquette">Crédit{{ suffixe }}</span>
            <span class="lel__valeur">{{ l.credit ? fmt(l.credit) : '—' }}</span>
          </div>
        </div>
        <div v-if="fmtTaux && l.tauxApplique != null" class="lel__taux">Taux appliqué : {{ fmtTaux(l.tauxApplique) }}</div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.lel__cartes { display: none; margin: 0; padding: 0; list-style: none; }

@media screen and (max-width: 959px) {
  .lel__tableau { display: none; }
  .lel__cartes {
    display: grid;
    gap: 10px;
    grid-template-columns: minmax(0, 1fr);
  }
}
@media screen and (min-width: 600px) and (max-width: 959px) {
  .lel__cartes { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

.lel__carte {
  min-width: 0;
  padding: 12px 12px 10px;
  background: #fff;
  border: 1px solid #eef0f2;
  border-radius: 12px;
}
.lel__compte { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; }
.lel__numero { font-size: 0.95rem; font-weight: 800; color: #0f172a; }
.lel__compte-libelle { min-width: 0; font-size: 0.85rem; font-weight: 500; color: #475569; overflow-wrap: anywhere; }
.lel__libelle { margin-top: 4px; font-size: 0.82rem; line-height: 1.35; color: #6b7280; overflow-wrap: anywhere; }
.lel__montants { display: flex; gap: 8px; margin-top: 10px; }
.lel__montant {
  flex: 1 1 0;
  min-width: min-content;
  padding: 7px 10px 8px;
  background: var(--color-primary-lighter);
  border-radius: 10px;
}
.lel__montant--vide { background: #f6f8fa; }
.lel__etiquette {
  display: block;
  font-size: 0.64rem;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  white-space: nowrap;
  color: #94a3b8;
}
.lel__valeur {
  display: block;
  margin-top: 2px;
  font-size: 1rem;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: #0f172a;
}
.lel__montant--vide .lel__valeur { font-weight: 500; color: #94a3b8; }
.lel__taux { margin-top: 8px; font-size: 0.74rem; color: #94a3b8; }
</style>
