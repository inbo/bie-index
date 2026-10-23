package au.org.ala.bie

import com.google.common.io.Resources
import grails.testing.services.ServiceUnitTest
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.util.SimpleOrderedMap
import spock.lang.Specification

/**
 * See the API for {@link grails.test.mixin.services.ServiceUnitTestMixin} for usage instructions
 */
class IndexServiceSpec extends Specification implements ServiceUnitTest<IndexService> {

    SolrClient liveSolrClient

    void setup() {
        liveSolrClient = Stub(SolrClient)

        service.liveSolrClient = liveSolrClient
    }

    void cleanup() {
    }

    void "test getIndexFieldDetails"() {
        setup:
        def resource = Resources.getResource(resourceLocation)
        def queryResponse = new QueryResponse(new XMLResponseParser().processResponse(resource.newReader()), liveSolrClient)
        def rootNode = new XmlParser().parse(resource.newReader())
        def fields = rootNode.lst.find { it.@name == 'fields' }.lst
        liveSolrClient.query(_) >> queryResponse

        when:
        def result = service.getIndexFieldDetails(*names)

        then:
        result.size() == fields.size()
        names.every { name ->
            result.find { it.name == name }
        }

        where:
        resourceLocation | names
        'resources/luke-response.xml' | []
        'resources/luke-response-acceptedConceptID.xml' | ['acceptedConceptID']
    }

    void "test coresByShard"() {
        expect:
        IndexService.coresByShard(cores) == groups

        where:
        cores                                                                                               | groups
        []                                                                                                  | [:]
        ['bie-efs-a_shard2_replica_n4']                                                                     | ['bie-efs-a_shard2': ['bie-efs-a_shard2_replica_n4']]
        ['bie-efs-a_shard6_replica_n20', 'bie-efs-a_shard2_replica_n4', 'bie-efs-a_shard6_replica_n22']     | ['bie-efs-a_shard6': ['bie-efs-a_shard6_replica_n20', 'bie-efs-a_shard6_replica_n22'], 'bie-efs-a_shard2': ['bie-efs-a_shard2_replica_n4']]
        ['bie']                                                                                             | ['bie': ['bie']]
    }

    void "test activeCores"() {
        given:
        // The cluster section of a CLUSTERSTATUS response is a NamedList whose
        // collection/shard/replica details are plain maps parsed from the cluster
        // state JSON, e.g. collections = { bie-a = { shards = { shard1 = {
        // replicas = { core_node3 = { core = '...', state = 'active' } } } } } }
        def collections = new SimpleOrderedMap<>()
        collections.add('bie-efs-a', [
            aliases: ['bie'],
            shards: [
                shard6: [replicas: [
                    core_node20: [core: 'bie-efs-a_shard6_replica_n20', state: 'active'],
                    core_node22: [core: 'bie-efs-a_shard6_replica_n22', state: 'down']
                ]]
            ]
        ])
        def cluster = new SimpleOrderedMap<>()
        cluster.add('collections', collections)

        expect:
        // The collection name matches
        IndexService.activeCores('bie-efs-a', cluster) == ['bie-efs-a_shard6_replica_n20']
        // ... and so does an alias pointing at it
        IndexService.activeCores('bie', cluster) == ['bie-efs-a_shard6_replica_n20']
        // All shards contribute their active replicas
        collections.add('bie-offline', [shards: [
            shard1: [replicas: [core_node2: [core: 'bie-efs-b_shard1_replica_n2', state: 'active']]],
            shard2: [replicas: [core_node4: [core: 'bie-efs-b_shard2_replica_n3', state: 'down']]]
        ]])
        IndexService.activeCores('bie-offline', cluster) == ['bie-efs-b_shard1_replica_n2']
        // Replicas record the core name in 'core', falling back to 'name'
        collections.add('plain', [shards: [shard1: [replicas: [core_node1: [name: 'plain_shard1_replica_n1', state: 'active']]]]])
        IndexService.activeCores('plain', cluster) == ['plain_shard1_replica_n1']
    }

    void "test activeCores missing collection"() {
        given:
        def cluster = new SimpleOrderedMap<>()
        cluster.add('collections', new SimpleOrderedMap<>())

        expect:
        IndexService.activeCores('bie', null) == null
        IndexService.activeCores('bie', cluster) == null
    }

}
