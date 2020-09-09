/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.ratings.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.ObservationManager;
import org.xwiki.ratings.AverageRating;
import org.xwiki.ratings.Rating;
import org.xwiki.ratings.RatingsConfiguration;
import org.xwiki.ratings.RatingsException;
import org.xwiki.ratings.RatingsManager.RatingQueryField;
import org.xwiki.ratings.events.CreatedRatingEvent;
import org.xwiki.ratings.events.DeletedRatingEvent;
import org.xwiki.ratings.events.UpdatedAverageRatingEvent;
import org.xwiki.ratings.events.UpdatedRatingEvent;
import org.xwiki.ratings.internal.averagerating.AverageRatingManager;
import org.xwiki.ratings.internal.averagerating.DefaultAverageRating;
import org.xwiki.search.solr.Solr;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultRatingsManager}.
 *
 * @version $Id$
 * @since 12.8RC1
 */
@ComponentTest
public class DefaultRatingsManagerTest
{
    @InjectMockComponents
    private DefaultRatingsManager manager;

    @MockComponent
    private SolrUtils solrUtils;

    @MockComponent
    private Solr solr;

    @MockComponent
    private UserReferenceSerializer<String> userReferenceSerializer;

    @MockComponent
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @MockComponent
    private UserReferenceResolver<String> userReferenceResolver;

    @MockComponent
    private EntityReferenceResolver<String> entityReferenceResolver;

    @MockComponent
    private ObservationManager observationManager;

    @MockComponent
    private RatingsConfiguration configuration;

    @MockComponent
    private AverageRatingManager averageRatingManager;

    @Mock
    private SolrClient solrClient;

    @Mock
    private SolrDocumentList documentList;

    @BeforeComponent
    void beforeComponent(MockitoComponentManager componentManager) throws Exception
    {
        componentManager.registerComponent(ComponentManager.class, "context", componentManager);
    }

    @BeforeEach
    void setup(MockitoComponentManager componentManager) throws Exception
    {
        this.manager.setRatingConfiguration(configuration);
        when(this.solrUtils.toFilterQueryString(any()))
            .then(invocationOnMock -> invocationOnMock.getArgument(0).toString().replaceAll(":", "\\\\:"));
        when(this.solrUtils.getId(any()))
            .then(invocationOnMock -> ((SolrDocument) invocationOnMock.getArgument(0)).get("id"));
        when(this.solrUtils.get(any(), any()))
            .then(invocationOnMock ->
                ((SolrDocument) invocationOnMock.getArgument(1)).get((String) invocationOnMock.getArgument(0)));
        doAnswer(invocationOnMock -> {
            String fieldName = invocationOnMock.getArgument(0);
            Object fieldValue = invocationOnMock.getArgument(1);
            SolrInputDocument inputDocument = invocationOnMock.getArgument(2);
            inputDocument.setField(fieldName, fieldValue);
            return null;
        }).when(this.solrUtils).set(any(), any(Object.class), any());
        doAnswer(invocationOnMock -> {
            Object fieldValue = invocationOnMock.getArgument(0);
            SolrInputDocument inputDocument = invocationOnMock.getArgument(1);
            inputDocument.setField("id", fieldValue);
            return null;
        }).when(this.solrUtils).setId(any(), any());
        when(this.configuration.getAverageRatingStorageHint()).thenReturn("averageHint");
        componentManager.registerComponent(AverageRatingManager.class, "averageHint", this.averageRatingManager);
    }

    private QueryResponse prepareSolrClientQueryWhenStatement(SolrClient solrClient, SolrQuery expectedQuery)
        throws Exception
    {
        QueryResponse response = mock(QueryResponse.class);
        when(solrClient.query(any())).then(invocationOnMock -> {
            SolrQuery givenQuery = invocationOnMock.getArgument(0);
            assertEquals(expectedQuery.getQuery(), givenQuery.getQuery());
            assertArrayEquals(expectedQuery.getFilterQueries(), givenQuery.getFilterQueries());
            assertEquals(expectedQuery.getRows(), givenQuery.getRows());
            assertEquals(expectedQuery.getStart(), givenQuery.getStart());
            assertEquals(expectedQuery.getSorts(), givenQuery.getSorts());
            return response;
        });
        return response;
    }

    @Test
    void countRatings() throws Exception
    {
        UserReference userReference = mock(UserReference.class);
        EntityReference reference = new EntityReference("toto", EntityType.BLOCK);
        Map<RatingQueryField, Object> queryParameters = new LinkedHashMap<>();
        queryParameters.put(RatingQueryField.ENTITY_REFERENCE, reference);
        queryParameters.put(RatingQueryField.USER_REFERENCE, userReference);
        queryParameters.put(RatingQueryField.SCALE, 12);

        String managerId = "managerTest";
        this.manager.setIdentifer(managerId);
        when(this.configuration.hasDedicatedCore()).thenReturn(true);
        when(this.solr.getClient(managerId)).thenReturn(this.solrClient);

        when(this.entityReferenceSerializer.serialize(reference)).thenReturn("block:toto");
        when(this.userReferenceSerializer.serialize(userReference)).thenReturn("user:Foobar");
        String query = "filter(reference:block\\:toto) AND filter(author:user\\:Foobar) "
            + "AND filter(scale:12) AND filter(managerId:managerTest)";
        SolrQuery expectedQuery = new SolrQuery().addFilterQuery(query).setStart(0).setRows(0);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);
        when(response.getResults()).thenReturn(this.documentList);
        when(this.documentList.getNumFound()).thenReturn(455L);

        assertEquals(455L, this.manager.countRatings(queryParameters));
    }

    @Test
    void getRatings() throws Exception
    {
        UserReference userReference = mock(UserReference.class);
        Map<RatingQueryField, Object> queryParameters = new LinkedHashMap<>();
        queryParameters.put(RatingQueryField.ENTITY_TYPE, EntityType.PAGE_ATTACHMENT);
        queryParameters.put(RatingQueryField.USER_REFERENCE, userReference);
        queryParameters.put(RatingQueryField.SCALE, "6");

        String managerId = "otherId";
        this.manager.setIdentifer(managerId);
        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        when(this.userReferenceSerializer.serialize(userReference)).thenReturn("user:barfoo");
        when(this.userReferenceResolver.resolve("user:barfoo")).thenReturn(userReference);
        String query = "filter(entityType:PAGE_ATTACHMENT) AND filter(author:user\\:barfoo) "
            + "AND filter(scale:6) AND filter(managerId:otherId)";

        int offset = 12;
        int limit = 42;
        String orderField = RatingQueryField.USER_REFERENCE.getFieldName();
        boolean asc = false;
        SolrQuery expectedQuery = new SolrQuery().addFilterQuery(query)
            .setStart(offset)
            .setRows(limit)
            .addSort(orderField, SolrQuery.ORDER.desc);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);
        when(response.getResults()).thenReturn(this.documentList);

        Map<String, Object> documentResult = new HashMap<>();
        documentResult.put("id", "result1");
        documentResult.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE_ATTACHMENT");
        documentResult.put(RatingQueryField.MANAGER_ID.getFieldName(), "otherId");
        documentResult.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(1));
        documentResult.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(1111));
        documentResult.put(RatingQueryField.VOTE.getFieldName(), 8);
        documentResult.put(RatingQueryField.SCALE.getFieldName(), 10);
        documentResult.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "attachment:Foo");
        EntityReference reference1 = new EntityReference("Foo", EntityType.PAGE_ATTACHMENT);
        when(this.entityReferenceResolver.resolve("attachment:Foo", EntityType.PAGE_ATTACHMENT)).thenReturn(reference1);
        documentResult.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:barfoo");
        SolrDocument result1 = new SolrDocument(documentResult);

        documentResult = new HashMap<>();
        documentResult.put("id", "result2");
        documentResult.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE_ATTACHMENT");
        documentResult.put(RatingQueryField.MANAGER_ID.getFieldName(), "otherId");
        documentResult.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(2));
        documentResult.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(2222));
        documentResult.put(RatingQueryField.VOTE.getFieldName(), 1);
        documentResult.put(RatingQueryField.SCALE.getFieldName(), 10);
        documentResult.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "attachment:Bar");
        EntityReference reference2 = new EntityReference("Bar", EntityType.PAGE_ATTACHMENT);
        when(this.entityReferenceResolver.resolve("attachment:Bar", EntityType.PAGE_ATTACHMENT)).thenReturn(reference2);
        documentResult.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:barfoo");
        SolrDocument result2 = new SolrDocument(documentResult);

        documentResult = new HashMap<>();
        documentResult.put("id", "result3");
        documentResult.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE_ATTACHMENT");
        documentResult.put(RatingQueryField.MANAGER_ID.getFieldName(), "otherId");
        documentResult.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(3));
        documentResult.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(3333));
        documentResult.put(RatingQueryField.VOTE.getFieldName(), 3);
        documentResult.put(RatingQueryField.SCALE.getFieldName(), 10);
        documentResult.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "attachment:Baz");
        EntityReference reference3 = new EntityReference("Baz", EntityType.PAGE_ATTACHMENT);
        when(this.entityReferenceResolver.resolve("attachment:Baz", EntityType.PAGE_ATTACHMENT)).thenReturn(reference3);
        documentResult.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:barfoo");
        SolrDocument result3 = new SolrDocument(documentResult);

        when(this.documentList.stream()).thenReturn(Arrays.asList(result1, result2, result3).stream());

        List<Rating> expectedRatings = Arrays.asList(
            new DefaultRating("result1")
                .setManagerId("otherId")
                .setCreatedAt(new Date(1))
                .setUpdatedAt(new Date(1111))
                .setVote(8)
                .setReference(reference1)
                .setAuthor(userReference)
                .setScale(10),

            new DefaultRating("result2")
                .setManagerId("otherId")
                .setCreatedAt(new Date(2))
                .setUpdatedAt(new Date(2222))
                .setVote(1)
                .setReference(reference2)
                .setAuthor(userReference)
                .setScale(10),

            new DefaultRating("result3")
                .setManagerId("otherId")
                .setCreatedAt(new Date(3))
                .setUpdatedAt(new Date(3333))
                .setVote(3)
                .setReference(reference3)
                .setAuthor(userReference)
                .setScale(10)
        );
        assertEquals(expectedRatings,
            this.manager.getRatings(queryParameters, offset, limit, RatingQueryField.USER_REFERENCE, asc));
    }

    @Test
    void getAverageRating() throws Exception
    {
        String managerId = "averageId2";
        this.manager.setIdentifer(managerId);
        EntityReference reference = new EntityReference("xwiki:Something", EntityType.PAGE);
        AverageRating expectedAverageRating = new DefaultAverageRating("average1")
            .setAverageVote(2.341f)
            .setTotalVote(242)
            .setUpdatedAt(new Date(42))
            .setManagerId(managerId)
            .setScale(12)
            .setReference(reference);
        when(this.averageRatingManager.getAverageRating(reference)).thenReturn(expectedAverageRating);

        AverageRating averageRating = this.manager.getAverageRating(reference);
        assertEquals(expectedAverageRating, averageRating);
    }

    @Test
    void removeRatingNotExisting() throws Exception
    {
        String ratingingId = "ratinging389";
        String managerId = "removeRating1";
        this.manager.setIdentifer(managerId);
        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        String query = "filter(id:ratinging389) AND filter(managerId:removeRating1)";
        SolrQuery expectedQuery = new SolrQuery()
            .addFilterQuery(query)
            .setStart(0)
            .setRows(1)
            .setSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);
        when(response.getResults()).thenReturn(this.documentList);
        assertFalse(this.manager.removeRating(ratingingId));
        verify(this.solrClient, never()).deleteById(any(String.class));
    }

    @Test
    void removeRatingExisting() throws Exception
    {
        String ratingingId = "ratinging429";
        String managerId = "removeRating2";
        this.manager.setIdentifer(managerId);
        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        String query = "filter(id:ratinging429) AND filter(managerId:removeRating2)";
        SolrQuery expectedQuery = new SolrQuery()
            .addFilterQuery(query)
            .setStart(0)
            .setRows(1)
            .setSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);
        when(response.getResults()).thenReturn(this.documentList);
        when(this.documentList.isEmpty()).thenReturn(false);

        Map<String, Object> documentResult = new HashMap<>();
        documentResult.put("id", ratingingId);
        documentResult.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE_ATTACHMENT");
        documentResult.put(RatingQueryField.MANAGER_ID.getFieldName(), managerId);
        documentResult.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(1));
        documentResult.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(1111));
        documentResult.put(RatingQueryField.VOTE.getFieldName(), 8);
        documentResult.put(RatingQueryField.SCALE.getFieldName(), 10);
        documentResult.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "attachment:Foo");
        EntityReference reference1 = new EntityReference("Foo", EntityType.PAGE_ATTACHMENT);
        when(this.entityReferenceResolver.resolve("attachment:Foo", EntityType.PAGE_ATTACHMENT)).thenReturn(reference1);
        when(this.entityReferenceSerializer.serialize(reference1)).thenReturn("attachment:Foo");
        documentResult.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:barfoo");
        UserReference userReference = mock(UserReference.class);
        when(this.userReferenceResolver.resolve("user:barfoo")).thenReturn(userReference);
        SolrDocument result1 = new SolrDocument(documentResult);
        when(this.documentList.stream()).thenReturn(Collections.singletonList(result1).stream());

        Rating rating = new DefaultRating(ratingingId)
            .setReference(reference1)
            .setManagerId(managerId)
            .setCreatedAt(new Date(1))
            .setUpdatedAt(new Date(1111))
            .setAuthor(userReference)
            .setScale(10)
            .setVote(8);

        // Average rating handling
        when(this.configuration.storeAverage()).thenReturn(true);

        assertTrue(this.manager.removeRating(ratingingId));
        verify(this.solrClient).deleteById(ratingingId);
        verify(this.solrClient).commit();
        verify(this.observationManager).notify(any(DeletedRatingEvent.class), eq(managerId), eq(rating));
        verify(this.configuration).storeAverage();
        verify(this.averageRatingManager).removeVote(reference1, 8);
    }

    @Test
    void saveRatingOutScale()
    {
        when(this.configuration.getScale()).thenReturn(5);
        this.manager.setIdentifer("saveRating1");
        RatingsException exception = assertThrows(RatingsException.class, () -> {
            this.manager.saveRating(new EntityReference("test", EntityType.PAGE), mock(UserReference.class), -1);
        });
        assertEquals("The vote [-1] is out of scale [5] for [saveRating1] ranking manager.", exception.getMessage());

        exception = assertThrows(RatingsException.class, () -> {
            this.manager.saveRating(new EntityReference("test", EntityType.PAGE), mock(UserReference.class), 8);
        });
        assertEquals("The vote [8] is out of scale [5] for [saveRating1] ranking manager.", exception.getMessage());
    }

    @Test
    void saveRatingZeroNotExisting() throws Exception
    {
        String managerId = "saveRating2";
        this.manager.setIdentifer(managerId);
        int scale = 10;
        when(this.configuration.getScale()).thenReturn(scale);
        EntityReference reference = new EntityReference("foobar", EntityType.PAGE);
        when(this.entityReferenceSerializer.serialize(reference)).thenReturn("wiki:foobar");
        when(this.entityReferenceResolver.resolve("wiki:foobar", EntityType.PAGE)).thenReturn(reference);
        UserReference userReference = mock(UserReference.class);
        when(this.userReferenceSerializer.serialize(userReference)).thenReturn("user:Toto");
        when(this.userReferenceResolver.resolve("user:Toto")).thenReturn(userReference);

        String filterQuery = "filter(reference:wiki\\:foobar) AND filter(entityType:PAGE) "
            + "AND filter(author:user\\:Toto) AND filter(managerId:saveRating2)";
        SolrQuery expectedQuery = new SolrQuery()
            .addFilterQuery(filterQuery)
            .setStart(0)
            .setRows(1)
            .addSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);

        // We don't mock stream behaviour, so that the returned result is empty.
        when(response.getResults()).thenReturn(this.documentList);

        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        // Check if we don't store 0
        when(this.configuration.storeZero()).thenReturn(false);
        assertNull(this.manager.saveRating(reference, userReference, 0));

        // Check if we store 0
        when(this.configuration.storeZero()).thenReturn(true);
        // Handle Average rating
        when(this.configuration.storeAverage()).thenReturn(true);

        DefaultRating expectedRating = new DefaultRating("")
            .setManagerId(managerId)
            .setReference(reference)
            .setCreatedAt(new Date())
            .setUpdatedAt(new Date())
            .setVote(0)
            .setScale(scale)
            .setAuthor(userReference);

        SolrInputDocument expectedInputDocument = new SolrInputDocument();
        expectedInputDocument.setField("id", "");
        expectedInputDocument.setField(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "wiki:foobar");
        expectedInputDocument.setField(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE");
        expectedInputDocument.setField(RatingQueryField.CREATED_DATE.getFieldName(), new Date());
        expectedInputDocument.setField(RatingQueryField.UPDATED_DATE.getFieldName(), new Date());
        expectedInputDocument.setField(RatingQueryField.USER_REFERENCE.getFieldName(), "user:Toto");
        expectedInputDocument.setField(RatingQueryField.SCALE.getFieldName(), scale);
        expectedInputDocument.setField(RatingQueryField.MANAGER_ID.getFieldName(), managerId);
        expectedInputDocument.setField(RatingQueryField.VOTE.getFieldName(), 0);

        when(this.solrClient.add(any(SolrInputDocument.class))).then(invocationOnMock -> {
            SolrInputDocument obtainedInputDocument = invocationOnMock.getArgument(0);
            Date updatedAt = (Date) obtainedInputDocument.getFieldValue(RatingQueryField.UPDATED_DATE.getFieldName());
            Date createdAt = (Date) obtainedInputDocument.getFieldValue(RatingQueryField.CREATED_DATE.getFieldName());
            String id = (String) obtainedInputDocument.getFieldValue("id");
            expectedInputDocument.setField(RatingQueryField.CREATED_DATE.getFieldName(), createdAt);
            expectedInputDocument.setField(RatingQueryField.UPDATED_DATE.getFieldName(), updatedAt);
            expectedInputDocument.setField("id", id);

            expectedRating
                .setId(id)
                .setCreatedAt(createdAt)
                .setUpdatedAt(updatedAt);
            // We rely on the toString method since there's no proper equals method
            assertEquals(expectedInputDocument.toString(), obtainedInputDocument.toString());
            return null;
        });

        assertEquals(expectedRating, this.manager.saveRating(reference, userReference, 0));
        verify(this.solrClient).add(any(SolrInputDocument.class));
        verify(this.solrClient).commit();
        verify(this.observationManager).notify(any(CreatedRatingEvent.class), eq(managerId), eq(expectedRating));
        verify(this.averageRatingManager).addVote(reference, 0);
    }

    @Test
    void saveRatingExisting() throws Exception
    {
        String managerId = "saveRating3";
        this.manager.setIdentifer(managerId);
        int scale = 8;
        int newVote = 2;
        int oldVote = 3;
        when(this.configuration.getScale()).thenReturn(scale);
        EntityReference reference = new EntityReference("foobar", EntityType.PAGE);
        when(this.entityReferenceSerializer.serialize(reference)).thenReturn("wiki:foobar");
        when(this.entityReferenceResolver.resolve("wiki:foobar", EntityType.PAGE)).thenReturn(reference);
        UserReference userReference = mock(UserReference.class);
        when(this.userReferenceSerializer.serialize(userReference)).thenReturn("user:Toto");
        when(this.userReferenceResolver.resolve("user:Toto")).thenReturn(userReference);

        String filterQuery = "filter(reference:wiki\\:foobar) AND filter(entityType:PAGE) "
            + "AND filter(author:user\\:Toto) AND filter(managerId:saveRating3)";
        SolrQuery expectedQuery = new SolrQuery()
            .addFilterQuery(filterQuery)
            .setStart(0)
            .setRows(1)
            .addSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);
        QueryResponse response = prepareSolrClientQueryWhenStatement(this.solrClient, expectedQuery);

        // We don't mock stream behaviour, so that the returned result is empty.
        when(response.getResults()).thenReturn(this.documentList);

        Map<String, Object> fieldMap = new HashMap<>();
        fieldMap.put("id", "myRating");
        fieldMap.put(RatingQueryField.VOTE.getFieldName(), oldVote);
        fieldMap.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(422));
        fieldMap.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(422));
        fieldMap.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:Toto");
        fieldMap.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "wiki:foobar");
        fieldMap.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE");
        fieldMap.put(RatingQueryField.SCALE.getFieldName(), scale);
        fieldMap.put(RatingQueryField.MANAGER_ID.getFieldName(), managerId);

        SolrDocument solrDocument = new SolrDocument(fieldMap);
        when(this.documentList.stream()).thenReturn(Collections.singletonList(solrDocument).stream());

        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        when(this.configuration.storeZero()).thenReturn(false);

        // Handle Average rating
        when(this.configuration.storeAverage()).thenReturn(true);

        DefaultRating expectedRating = new DefaultRating("myRating")
            .setManagerId(managerId)
            .setReference(reference)
            .setCreatedAt(new Date(422))
            .setUpdatedAt(new Date())
            .setVote(newVote)
            .setScale(scale)
            .setAuthor(userReference);

        SolrInputDocument expectedInputDocument = new SolrInputDocument();
        expectedInputDocument.setField("id", "myRating");
        expectedInputDocument.setField(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "wiki:foobar");
        expectedInputDocument.setField(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE");
        expectedInputDocument.setField(RatingQueryField.CREATED_DATE.getFieldName(), new Date(422));
        expectedInputDocument.setField(RatingQueryField.UPDATED_DATE.getFieldName(), new Date());
        expectedInputDocument.setField(RatingQueryField.USER_REFERENCE.getFieldName(), "user:Toto");
        expectedInputDocument.setField(RatingQueryField.SCALE.getFieldName(), scale);
        expectedInputDocument.setField(RatingQueryField.MANAGER_ID.getFieldName(), managerId);
        expectedInputDocument.setField(RatingQueryField.VOTE.getFieldName(), newVote);

        when(this.solrClient.add(any(SolrInputDocument.class))).then(invocationOnMock -> {
            SolrInputDocument obtainedInputDocument = invocationOnMock.getArgument(0);
            Date updatedAt = (Date) obtainedInputDocument.getFieldValue(RatingQueryField.UPDATED_DATE.getFieldName());
            expectedInputDocument.setField(RatingQueryField.UPDATED_DATE.getFieldName(), updatedAt);

            expectedRating
                .setUpdatedAt(updatedAt);
            // We rely on the toString method since there's no proper equals method
            assertEquals(expectedInputDocument.toString(), obtainedInputDocument.toString());
            return null;
        });

        assertEquals(expectedRating, this.manager.saveRating(reference, userReference, newVote));
        verify(this.solrClient).add(any(SolrInputDocument.class));
        verify(this.solrClient).commit();
        verify(this.observationManager).notify(new UpdatedRatingEvent(expectedRating, oldVote), managerId,
            expectedRating);
        verify(this.averageRatingManager).updateVote(reference, oldVote, newVote);
    }

    @Test
    void saveRatingExistingToZero() throws Exception
    {
        String managerId = "saveRating4";
        this.manager.setIdentifer(managerId);
        int scale = 8;
        int newVote = 0;
        int oldVote = 3;
        when(this.configuration.getScale()).thenReturn(scale);
        EntityReference reference = new EntityReference("foobar", EntityType.PAGE);
        when(this.entityReferenceSerializer.serialize(reference)).thenReturn("wiki:foobar");
        when(this.entityReferenceResolver.resolve("wiki:foobar", EntityType.PAGE)).thenReturn(reference);
        UserReference userReference = mock(UserReference.class);
        when(this.userReferenceSerializer.serialize(userReference)).thenReturn("user:Toto");
        when(this.userReferenceResolver.resolve("user:Toto")).thenReturn(userReference);

        String filterQuery = "filter(reference:wiki\\:foobar) AND filter(entityType:PAGE) "
            + "AND filter(author:user\\:Toto) AND filter(managerId:saveRating4)";
        SolrQuery firstExpectedQuery = new SolrQuery()
            .addFilterQuery(filterQuery)
            .setStart(0)
            .setRows(1)
            .addSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);

        Map<String, Object> fieldMap = new HashMap<>();
        fieldMap.put("id", "myRating");
        fieldMap.put(RatingQueryField.VOTE.getFieldName(), oldVote);
        fieldMap.put(RatingQueryField.CREATED_DATE.getFieldName(), new Date(422));
        fieldMap.put(RatingQueryField.UPDATED_DATE.getFieldName(), new Date(422));
        fieldMap.put(RatingQueryField.USER_REFERENCE.getFieldName(), "user:Toto");
        fieldMap.put(RatingQueryField.ENTITY_REFERENCE.getFieldName(), "wiki:foobar");
        fieldMap.put(RatingQueryField.ENTITY_TYPE.getFieldName(), "PAGE");
        fieldMap.put(RatingQueryField.SCALE.getFieldName(), scale);
        fieldMap.put(RatingQueryField.MANAGER_ID.getFieldName(), managerId);

        SolrDocument solrDocument = new SolrDocument(fieldMap);
        when(this.documentList.stream())
            .thenReturn(Collections.singletonList(solrDocument).stream())
            .thenReturn(Collections.singletonList(solrDocument).stream());
        // Those are used for deletion.
        when(this.documentList.isEmpty()).thenReturn(false);
        when(this.documentList.get(0)).thenReturn(solrDocument);

        when(this.configuration.hasDedicatedCore()).thenReturn(false);
        when(this.solr.getClient(RatingSolrCoreInitializer.DEFAULT_RATING_SOLR_CORE)).thenReturn(this.solrClient);

        // Handle Average rating
        when(this.configuration.storeAverage()).thenReturn(true);

        DefaultRating oldRating = new DefaultRating("myRating")
            .setManagerId(managerId)
            .setReference(reference)
            .setCreatedAt(new Date(422))
            .setUpdatedAt(new Date(422))
            .setVote(oldVote)
            .setScale(scale)
            .setAuthor(userReference);

        filterQuery = "filter(id:myRating) AND filter(managerId:saveRating4)";
        SolrQuery secondExpectedQuery = new SolrQuery()
            .addFilterQuery(filterQuery)
            .setStart(0)
            .setRows(1)
            .addSort(RatingQueryField.CREATED_DATE.getFieldName(), SolrQuery.ORDER.asc);

        QueryResponse response = mock(QueryResponse.class);
        final AtomicBoolean flag = new AtomicBoolean(false);
        when(solrClient.query(any())).then(invocationOnMock -> {
            SolrQuery givenQuery = invocationOnMock.getArgument(0);
            SolrQuery checkExpectedQuery;
            if (!flag.get()) {
                checkExpectedQuery = firstExpectedQuery;
                flag.set(true);
            } else {
                checkExpectedQuery = secondExpectedQuery;
            }

            assertEquals(checkExpectedQuery.getQuery(), givenQuery.getQuery());
            assertArrayEquals(checkExpectedQuery.getFilterQueries(), givenQuery.getFilterQueries());
            assertEquals(checkExpectedQuery.getRows(), givenQuery.getRows());
            assertEquals(checkExpectedQuery.getStart(), givenQuery.getStart());
            assertEquals(checkExpectedQuery.getSorts(), givenQuery.getSorts());
            return response;
        });
        when(response.getResults()).thenReturn(this.documentList);

        when(this.configuration.storeZero()).thenReturn(true);
        assertNull(this.manager.saveRating(reference, userReference, newVote));
        verify(this.solrClient, never()).add(any(SolrInputDocument.class));
        verify(this.solrClient).deleteById("myRating");
        verify(this.solrClient).commit();
        verify(this.observationManager).notify(any(DeletedRatingEvent.class), eq(managerId), eq(oldRating));
        verify(this.averageRatingManager).removeVote(reference, oldVote);
    }
}
