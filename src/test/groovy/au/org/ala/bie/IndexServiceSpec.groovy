package au.org.ala.bie

import com.google.common.io.Resources
import grails.testing.services.ServiceUnitTest
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.apache.solr.client.solrj.response.QueryResponse
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
        expect:
        IndexService.activeCores(collection, cluster) == cores

        where:
        collection    | cluster                                                                                                                            | cores
        'bie'         | null                                                                                                                               | null
        'bie'         | [collections: [:]]                                                                                                                 | null
        'bie'         | [collections: [bie: [replicas: [core_node1: [name: 'bie_replica_n1', state: 'down']]]]]                                            | []
        'bie'         | [collections: [bie: [replicas: [core_node1: [name: 'bie_replica_n1', state: 'active'], core_node2: [name: 'bie_replica_n2', state: 'down']]]]] | ['bie_replica_n1']
        // An alias resolves to the collection it points to
        'bie'         | [collections: ['bie-efs-a': [aliases: ['bie'], replicas: [core_node23: [name: 'bie-efs-a_shard6_replica_n20', state: 'active']]]]] | ['bie-efs-a_shard6_replica_n20']
        // A real collection name also matches
        'bie-efs-a'   | [collections: ['bie-efs-a': [aliases: ['bie'], replicas: [core_node23: [name: 'bie-efs-a_shard6_replica_n20', state: 'active']]]]] | ['bie-efs-a_shard6_replica_n20']
    }

}
