package com.example.server.service;

import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgePersistenceTransactionProxyTest {

    @Test
    void springBeanIsTransactionalProxyWithRequiredAndRequiresNewMetadata() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
            KnowledgePersistenceService bean = context.getBean(KnowledgePersistenceService.class);

            assertTrue(AopUtils.isAopProxy(bean));
            assertPropagation(
                    "persistPreparedChunks",
                    new Class<?>[]{Long.class, Long.class, List.class},
                    TransactionDefinition.PROPAGATION_REQUIRED);
            assertPropagation(
                    "markFailed",
                    new Class<?>[]{Long.class, Long.class, String.class},
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    @Test
    void runtimeFailureThroughSpringProxyTriggersRollback() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
            KnowledgePersistenceService bean = context.getBean(KnowledgePersistenceService.class);
            PlatformTransactionManager transactionManager =
                    context.getBean(PlatformTransactionManager.class);
            KnowledgeDocumentMapper documentMapper = context.getBean(KnowledgeDocumentMapper.class);

            when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(null);

            assertThrows(KnowledgePersistenceService.ProcessingStateException.class,
                    () -> bean.persistPreparedChunks(10L, 20L, List.of(chunk())));

            verify(transactionManager).rollback(any(TransactionStatus.class));
        }
    }

    private void assertPropagation(String methodName, Class<?>[] parameterTypes, int expected)
            throws Exception {
        Method method = KnowledgePersistenceService.class.getMethod(methodName, parameterTypes);
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        TransactionAttribute attribute = source.getTransactionAttribute(
                method, KnowledgePersistenceService.class);
        assertNotNull(attribute);
        assertEquals(expected, attribute.getPropagationBehavior());
    }

    private com.example.server.entity.KnowledgeChunk chunk() {
        com.example.server.entity.KnowledgeChunk chunk = new com.example.server.entity.KnowledgeChunk();
        chunk.setDocumentId(10L);
        chunk.setUserId(20L);
        chunk.setChunkIndex(0);
        return chunk;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {

        @Bean
        KnowledgeDocumentMapper knowledgeDocumentMapper() {
            return mock(KnowledgeDocumentMapper.class);
        }

        @Bean
        KnowledgeChunkMapper knowledgeChunkMapper() {
            return mock(KnowledgeChunkMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                    .thenReturn(mock(TransactionStatus.class));
            return transactionManager;
        }

        @Bean
        KnowledgePersistenceService knowledgePersistenceService(
                KnowledgeDocumentMapper documentMapper,
                KnowledgeChunkMapper chunkMapper) {
            return new KnowledgePersistenceService(documentMapper, chunkMapper);
        }
    }
}
