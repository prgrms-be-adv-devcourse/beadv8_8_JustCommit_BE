// 프로젝트에서 Sprint가 비어 있는 하위 이슈에 상위 이슈의 Sprint를 채운다.
// 이미 Sprint가 있는 하위 이슈는 건드리지 않는다 (다음 스프린트로 옮긴 Task를 되돌리지 않기 위해).
// 사용: GH_TOKEN=... node .github/scripts/sync-sprint.mjs [--dry-run]
const ORG = process.env.PROJECT_OWNER ?? 'prgrms-be-adv-devcourse';
const PROJECT_NUMBER = Number(process.env.PROJECT_NUMBER ?? 79);
const FIELD = process.env.SPRINT_FIELD ?? 'Sprint';
const DRY_RUN = process.argv.includes('--dry-run');
const TOKEN = process.env.GH_TOKEN;

if (!TOKEN) {
  console.error('GH_TOKEN이 없습니다. 조직 프로젝트 쓰기 권한이 있는 토큰이 필요합니다.');
  process.exit(1);
}

async function graphql(query, variables) {
  const res = await fetch('https://api.github.com/graphql', {
    method: 'POST',
    headers: {
      Authorization: `bearer ${TOKEN}`,
      'Content-Type': 'application/json',
      'GraphQL-Features': 'sub_issues',
    },
    body: JSON.stringify({ query, variables }),
  });
  const body = await res.json();
  if (!res.ok || body.errors) {
    throw new Error(`GraphQL 실패: ${res.status} ${JSON.stringify(body.errors ?? body)}`);
  }
  return body.data;
}

const SPRINT_VALUE = `fieldValueByName(name: $field) { ... on ProjectV2ItemFieldIterationValue { iterationId title } }`;

const ITEMS = `
query($org: String!, $number: Int!, $field: String!, $after: String) {
  organization(login: $org) {
    projectV2(number: $number) {
      id
      field(name: $field) { ... on ProjectV2IterationField { id } }
      items(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id
          ${SPRINT_VALUE}
          content {
            ... on Issue {
              id
              number
              parent { id number }
            }
          }
        }
      }
    }
  }
}`;

const UPDATE = `
mutation($project: ID!, $item: ID!, $field: ID!, $iteration: String!) {
  updateProjectV2ItemFieldValue(input: {
    projectId: $project, itemId: $item, fieldId: $field, value: { iterationId: $iteration }
  }) { projectV2Item { id } }
}`;

async function loadItems() {
  const items = [];
  let projectId;
  let fieldId;
  let after = null;
  do {
    const data = await graphql(ITEMS, { org: ORG, number: PROJECT_NUMBER, field: FIELD, after });
    const project = data.organization.projectV2;
    projectId = project.id;
    fieldId = project.field?.id;
    items.push(...project.items.nodes);
    after = project.items.pageInfo.hasNextPage ? project.items.pageInfo.endCursor : null;
  } while (after);
  if (!fieldId) {
    throw new Error(`프로젝트 ${PROJECT_NUMBER}에 Iteration 필드 "${FIELD}"가 없습니다.`);
  }
  return { projectId, fieldId, items };
}

const { projectId, fieldId, items } = await loadItems();

// 상위 이슈의 Sprint는 같은 프로젝트 안의 항목에서 찾는다
const sprintByIssue = new Map();
for (const item of items) {
  if (item.content?.id && item.fieldValueByName) {
    sprintByIssue.set(item.content.id, item.fieldValueByName);
  }
}

let updated = 0;
for (const item of items) {
  const issue = item.content;
  if (!issue?.parent || item.fieldValueByName) continue;
  const parentSprint = sprintByIssue.get(issue.parent.id);
  if (!parentSprint) continue;

  console.log(`#${issue.number} ← 상위 #${issue.parent.number}의 ${parentSprint.title}${DRY_RUN ? ' (dry-run)' : ''}`);
  if (!DRY_RUN) {
    await graphql(UPDATE, { project: projectId, item: item.id, field: fieldId, iteration: parentSprint.iterationId });
  }
  updated++;
}
console.log(`항목 ${items.length}개 확인, ${updated}개 ${DRY_RUN ? '채울 예정' : '채움'}`);
