# 데이터 서버 OpenSearch: 공식 이미지에 한국어 분석기 nori를 더한다 (#295)
FROM opensearchproject/opensearch:2.19.2

# 상품 검색에 쓰지 않는 기본 플러그인을 뺀다 — t3.medium 메모리 절약
# 플러그인끼리 의존해서(예: skills → ml) 빼는 순서가 중요하다 — 남이 의존하는 것은 나중에
# 보안 플러그인은 DISABLE_SECURITY_PLUGIN으로 끄므로 그대로 둔다
RUN for p in \
      opensearch-skills opensearch-flow-framework opensearch-neural-search opensearch-ml \
      opensearch-security-analytics opensearch-anomaly-detection opensearch-alerting \
      opensearch-reports-scheduler opensearch-observability opensearch-notifications opensearch-notifications-core \
      opensearch-knn opensearch-ltr opensearch-geospatial opensearch-cross-cluster-replication \
      opensearch-asynchronous-search opensearch-sql query-insights opensearch-custom-codecs \
      opensearch-system-templates opensearch-performance-analyzer; do \
      /usr/share/opensearch/bin/opensearch-plugin remove "$p" || exit 1; \
    done \
 && /usr/share/opensearch/bin/opensearch-plugin install --batch analysis-nori
