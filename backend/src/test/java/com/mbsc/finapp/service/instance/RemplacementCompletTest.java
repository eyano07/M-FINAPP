package com.mbsc.finapp.service.instance;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Remplacement complet des tables (activation du mode local, resynchronisation d'une copie) : supprimer tout puis inserer
 * tout, au lieu de mettre les lignes a jour une a une (deux bases nees separement donnent les memes identifiants a des
 * lignes differentes, et la mise a jour d'une ligne viole alors une contrainte d'unicite : 25/10, comptes comptables).
 */
class RemplacementCompletTest {

    @Test
    void les_tables_sont_triees_parents_d_abord_les_cycles_en_dernier() {
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        parents.put("a_cycle", Set.of("b_cycle"));
        parents.put("articles", Set.of());
        parents.put("b_cycle", Set.of("a_cycle"));
        parents.put("lots_stock", Set.of("articles", "lignes_mouvement_stock"));
        parents.put("lignes_mouvement_stock", Set.of("mouvements_stock"));
        parents.put("mouvements_stock", Set.of());

        assertThat(ReplicationSchema.ordonner(parents))
            .containsExactly("articles", "mouvements_stock", "lignes_mouvement_stock", "lots_stock", "a_cycle", "b_cycle");
    }

    @Test
    void le_remplacement_supprime_les_enfants_puis_les_parents_et_insere_les_parents_puis_les_enfants() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReplicationSchema schema = schema("parents", "enfants");
        ReplicationApplicateur applicateur = new ReplicationApplicateur(jdbc, schema);

        applicateur.remplacerToutes(false);

        InOrder ordre = inOrder(jdbc);
        ordre.verify(jdbc).update("delete from public.\"enfants\"");
        ordre.verify(jdbc).update("delete from public.\"parents\"");
        ordre.verify(jdbc).update(startsWith("insert into public.\"parents\" (\"id\", \"nom\") select r.\"id\", r.\"nom\" "),
            eq("parents"));
        ordre.verify(jdbc).update(startsWith("insert into public.\"enfants\" "), eq("enfants"));
        // Sans fusion : pas de photographie des comptes, ni de mise a jour apres coup.
        verify(jdbc, never()).execute(contains("_fusion_users"));
        verify(jdbc, never()).update(startsWith("update public.users"));
    }

    @Test
    void la_resynchronisation_d_une_copie_garde_ce_que_la_copie_ecrit_elle_meme() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReplicationSchema schema = schema("users", "notifications");
        ReplicationApplicateur applicateur = new ReplicationApplicateur(jdbc, schema);

        applicateur.remplacerToutes(true);

        InOrder ordre = inOrder(jdbc);
        ordre.verify(jdbc).execute(argThat((String s) -> s != null && s.contains("_fusion_users") && s.contains("version_jetons")));
        ordre.verify(jdbc).execute(contains("_fusion_notifications"));
        ordre.verify(jdbc).update("delete from public.\"notifications\"");
        ordre.verify(jdbc).update("delete from public.\"users\"");
        ordre.verify(jdbc).update(startsWith("insert into public.\"users\" "), eq("users"));
        ordre.verify(jdbc).update(startsWith("insert into public.\"notifications\" "), eq("notifications"));
        ordre.verify(jdbc).update(startsWith("update public.users u set version_jetons = f.version_jetons"));
        ordre.verify(jdbc).update(argThat((String s) -> s != null && s.contains("ACTIVER_UTILISATEUR") && s.contains("EN_ATTENTE")));
        ordre.verify(jdbc).update(startsWith("update public.notifications n set lue = true"));
    }

    @Test
    void le_total_est_le_nombre_de_lignes_inserees() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReplicationSchema schema = schema("parents", "enfants");
        when(jdbc.update(startsWith("insert into public.\"parents\""), eq("parents"))).thenReturn(3);
        when(jdbc.update(startsWith("insert into public.\"enfants\""), eq("enfants"))).thenReturn(5);

        assertThat(new ReplicationApplicateur(jdbc, schema).remplacerToutes(false)).isEqualTo(8L);
    }

    /** Un catalogue de tables a deux colonnes (id, nom), la premiere citee etant referencee par la seconde. */
    private static ReplicationSchema schema(String parent, String enfant) {
        ReplicationSchema schema = mock(ReplicationSchema.class);
        Map<String, ReplicationSchema.Table> tables = new LinkedHashMap<>();
        for (String nom : List.of(parent, enfant)) {
            tables.put(nom, new ReplicationSchema.Table(nom, List.of("id", "nom"), List.of("id")));
        }
        when(schema.tables()).thenReturn(tables);
        when(schema.ordreParentsDabord()).thenReturn(List.of(parent, enfant));
        for (String nom : tables.keySet()) {
            when(schema.table(nom)).thenReturn(tables.get(nom));
        }
        return schema;
    }
}
