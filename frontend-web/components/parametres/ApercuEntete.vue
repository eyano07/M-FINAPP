<script setup lang="ts">
/**
 * Miniature d'une page A4 habillée selon un modèle de papier à en-tête et une couleur : reproduit à
 * petite échelle la mise en page des PDF du serveur (EnteteDocumentRendu) pour guider le choix de
 * l'administrateur. Purement décoratif : le nom du modèle est donné par le bouton qui l'entoure.
 */
import type { ModeleEntete } from '~/composables/useModeleEntete'

const props = defineProps<{
  modele: ModeleEntete
  couleur: string
  nom: string
  logoUrl?: string | null
}>()

const initiale = computed(() => (props.nom || '?').trim().charAt(0).toUpperCase())
</script>

<template>
  <div class="ae" :class="`ae--${modele.toLowerCase()}`" :style="{ '--ae-c': couleur }" aria-hidden="true">
    <div v-if="modele === 'LATERAL'" class="ae-rail"><i /></div>
    <div class="ae-tete">
      <div v-if="modele === 'CLASSIQUE'" class="ae-ruban">
        <i /><i /><i />
      </div>
      <i v-if="modele === 'EPURE'" class="ae-kicker" />
      <div class="ae-marque">
        <span class="ae-logo">
          <img v-if="logoUrl" :src="logoUrl" alt="">
          <template v-else>{{ initiale }}</template>
        </span>
        <span class="ae-textes">
          <span class="ae-nom">{{ nom || 'Votre société' }}</span>
          <i class="ae-trait ae-trait--court" />
          <span v-if="modele === 'ENCADRE'" class="ae-pastilles"><u /><u /><u /></span>
        </span>
      </div>
      <div v-if="modele !== 'CLASSIQUE' && modele !== 'ENCADRE'" class="ae-ids"><i /><i /><i /></div>
    </div>
    <div v-if="modele === 'CENTRE'" class="ae-ornement"><b /></div>
    <div class="ae-corps">
      <i /><i /><i /><i class="ae-trait--court" /><i /><i />
    </div>
    <div v-if="modele === 'CENTRE'" class="ae-ornement"><b /></div>
    <div class="ae-pied"><span><b /><i /></span><span><b /><i /></span><span><b /><i /></span></div>
  </div>
</template>

<style scoped>
.ae {
  --ae-c: #15803d;
  --ae-fonce: color-mix(in srgb, var(--ae-c) 68%, black);
  --ae-clair: color-mix(in srgb, var(--ae-c) 6%, white);
  --ae-mid: color-mix(in srgb, var(--ae-c) 34%, white);
  position: relative;
  aspect-ratio: 210 / 297;
  width: 100%;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.16);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  font-family: Inter, Helvetica, Arial, sans-serif;
}
.ae i { display: block; height: 3px; border-radius: 2px; background: #e5e7eb; }
.ae b { display: block; flex-shrink: 0; }

/* En-tête */
.ae-tete { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 9% 9% 4%; }
.ae-marque { display: flex; align-items: center; gap: 5px; min-width: 0; z-index: 1; }
.ae-logo {
  flex-shrink: 0; width: 15px; height: 15px; border-radius: 3px; background: var(--ae-c); color: #fff;
  font-size: 9px; font-weight: 700; display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.ae-logo img { width: 100%; height: 100%; object-fit: contain; background: #fff; }
.ae-textes { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ae-nom { font-size: 7px; font-weight: 800; color: var(--ae-fonce); text-transform: uppercase; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ae-trait--court { width: 60%; }
.ae-ids { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; width: 26%; flex-shrink: 0; }
.ae-ids i { width: 100%; background: #d1d5db; }
.ae-ids i:nth-child(2) { width: 80%; }

/* Corps */
.ae-corps { flex: 1; display: flex; flex-direction: column; gap: 5px; padding: 6% 9%; }
.ae-corps .ae-trait--court { width: 55%; }

/* Pied : trois coordonnées (pastille + trait) */
.ae-pied { display: flex; justify-content: space-between; gap: 6%; padding: 4% 9% 6%; }
.ae-pied span { flex: 1; display: flex; align-items: center; gap: 2px; }
.ae-pied b { width: 3px; height: 3px; border-radius: 50%; background: var(--ae-c); }
.ae-pied i { flex: 1; height: 2px; background: #d1d5db; }

/* Classique : ruban incliné à droite, pied teinté */
.ae--classique .ae-tete { border-bottom: 1px solid #e5e7eb; margin: 0 9%; padding: 9% 0 4%; }
.ae-ruban {
  position: absolute; right: -14%; top: 22%; width: 42%; height: 42%;
  background: linear-gradient(135deg, var(--ae-c), var(--ae-fonce));
  clip-path: polygon(8% 0, 100% 0, 100% 100%, 0 100%);
  display: flex; flex-direction: column; justify-content: center; gap: 2px; padding-left: 12%;
}
.ae-ruban i { background: rgba(255, 255, 255, 0.85); width: 60%; height: 2px; }
.ae--classique .ae-pied { background: color-mix(in srgb, var(--ae-c) 10%, white); }
.ae--classique .ae-pied b { display: none; }
.ae--classique .ae-pied i { background: var(--ae-c); opacity: 0.45; }

/* Bandeau : dégradé, cercles translucides, logo sur tuile blanche */
.ae--bandeau .ae-tete {
  padding: 8% 8%;
  background:
    radial-gradient(circle at 94% -18%, rgba(255, 255, 255, 0.2) 0 20px, transparent 21px),
    radial-gradient(circle at 70% 130%, rgba(255, 255, 255, 0.13) 0 15px, transparent 16px),
    linear-gradient(105deg, var(--ae-fonce), var(--ae-c));
}
.ae--bandeau .ae-logo { width: 20px; height: 20px; border-radius: 6px; background: #fff; color: var(--ae-c); }
.ae--bandeau .ae-nom { color: #fff; font-size: 7.5px; letter-spacing: 0.04em; }
.ae--bandeau .ae-textes i { background: rgba(255, 255, 255, 0.55); }
.ae--bandeau .ae-ids { border-left: 1px solid rgba(255, 255, 255, 0.4); padding-left: 4px; }
.ae--bandeau .ae-ids i { background: rgba(255, 255, 255, 0.75); }
.ae--bandeau .ae-pied { position: relative; margin: 0 8%; padding: 4% 0 6%; border-top: 1px solid #e5e7eb; }
.ae--bandeau .ae-pied::before { content: ""; position: absolute; top: -1.5px; left: 0; width: 14px; height: 2px; border-radius: 2px; background: var(--ae-c); }

/* Épuré : blanc, trait d'accent, texte noir, identifiants en colonne */
.ae--epure .ae-tete { border-bottom: 1px solid #e5e7eb; margin: 0 9%; padding: 13% 0 5%; }
.ae .ae-kicker { position: absolute; top: 7%; left: 0; width: 13px; height: 2px; background: var(--ae-c); }
.ae--epure .ae-nom { color: #111827; font-size: 7.5px; }
.ae--epure .ae-logo { background: transparent; color: var(--ae-c); border: 1px solid var(--ae-c); border-radius: 50%; }
.ae--epure .ae-ids i { background: color-mix(in srgb, var(--ae-c) 28%, #d1d5db); }
.ae--epure .ae-pied { border-top: 1px solid #e5e7eb; margin: 0 9%; padding: 4% 0 6%; justify-content: center; gap: 3px; }
.ae--epure .ae-pied span { flex: 0 0 22%; }
.ae--epure .ae-pied b { display: none; }

/* Institutionnel : centré, nom espacé, ornement à losange */
.ae--centre .ae-tete { flex-direction: column; margin: 0 9%; padding: 8% 0 3%; gap: 4px; }
.ae--centre .ae-marque { flex-direction: column; text-align: center; gap: 3px; }
.ae--centre .ae-textes { align-items: center; }
.ae--centre .ae-logo { border-radius: 50%; width: 17px; height: 17px; }
.ae--centre .ae-nom { letter-spacing: 0.2em; font-size: 6.6px; }
.ae--centre .ae-ids { flex-direction: row; width: 78%; justify-content: center; align-items: center; gap: 3px; }
.ae--centre .ae-ids i { width: 24%; }
.ae-ornement { position: relative; height: 7px; margin: 1% 9% 0; }
.ae-ornement::before { content: ""; position: absolute; left: 0; right: 0; top: 3px; height: 1px; background: var(--ae-mid); }
.ae-ornement b { position: absolute; left: 50%; top: 1px; width: 5px; height: 5px; margin-left: -2.5px; transform: rotate(45deg); background: var(--ae-c); box-shadow: 0 0 0 2px #fff; }
.ae--centre .ae-corps { padding-top: 4%; }
.ae--centre .ae-pied { justify-content: center; margin: 0 9%; padding: 3% 0 6%; }
.ae--centre .ae-pied span { flex: 0 0 24%; }
.ae--centre .ae-pied b { display: none; }

/* Latéral : rail de couleur, nom en vertical, en-tête sobre */
.ae-rail { position: absolute; left: 0; top: 0; bottom: 0; width: 5.5%; background: linear-gradient(180deg, var(--ae-c), var(--ae-fonce)); z-index: 2; }
.ae-rail i { position: absolute; left: 50%; bottom: 9px; width: 1.5px; height: 24px; margin-left: -0.75px; background: rgba(255, 255, 255, 0.85); }
.ae--lateral .ae-tete { position: relative; border-bottom: 1px solid #e5e7eb; margin: 0 9% 0 15%; padding: 9% 0 5%; }
.ae--lateral .ae-tete::after { content: ""; position: absolute; left: 0; bottom: -1.5px; width: 14px; height: 2px; border-radius: 2px; background: var(--ae-c); }
.ae--lateral .ae-nom { color: var(--ae-fonce); font-size: 7.5px; }
.ae--lateral .ae-ids { border-left: 2px solid var(--ae-c); padding-left: 3px; align-items: flex-start; }
.ae--lateral .ae-corps, .ae--lateral .ae-pied { padding-left: 15%; }

/* Encadré : carte arrondie, pastilles, pied en carte */
.ae--encadre .ae-tete {
  margin: 6% 5% 0; padding: 4.5% 5%; background: var(--ae-clair); border: 1px solid var(--ae-mid); border-radius: 7px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
}
.ae--encadre .ae-logo { width: 18px; height: 18px; border-radius: 5px; background: #fff; color: var(--ae-c); border: 1px solid var(--ae-mid); }
.ae--encadre .ae-nom { font-size: 7.2px; }
.ae-pastilles { display: flex; gap: 2px; margin-top: 1px; }
.ae-pastilles u { display: block; width: 15px; height: 5px; border-radius: 3px; background: color-mix(in srgb, var(--ae-c) 20%, white); }
.ae--encadre .ae-pied {
  margin: 0 5% 5%; padding: 3.5% 5%; background: var(--ae-clair); border: 1px solid var(--ae-mid); border-radius: 7px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
}
</style>
