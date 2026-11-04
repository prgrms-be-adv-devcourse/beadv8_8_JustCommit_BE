// 프로젝트에서 하위 이슈의 Sprint를 상위 이슈의 Sprint와 같게 맞춘다.
// 하위 Sprint가 비었거나 상위와 다르면 상위 값으로 덮어쓴다 (상위 Sprint를 옮기면 하위도 같이 옮겨진다).
// 상위 Sprint가 비어 있으면 하위는 그대로 둔다.
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

// 하위의 하위(Task 아래 Task)도 한 번에 맞도록, 바뀐 값이 없을 때까지 여러 번 훑는다
const MAX_PASSES = 5;
let updated = 0;
for (let pass = 0; pass < MAX_PASSES; pass++) {
  let changed = 0;
  for (const item of items) {
    const issue = item.content;
    if (!issue?.parent) continue;
    const parentSprint = sprintByIssue.get(issue.parent.id);
    if (!parentSprint) continue;
    const current = sprintByIssue.get(issue.id);
    if (current?.iterationId === parentSprint.iterationId) continue;

    const from = current ? current.title : '비어 있음';
    console.log(`#${issue.number}: ${from} → ${parentSprint.title} (상위 #${issue.parent.number})${DRY_RUN ? ' (dry-run)' : ''}`);
    if (!DRY_RUN) {
      await graphql(UPDATE, { project: projectId, item: item.id, field: fieldId, iteration: parentSprint.iterationId });
    }
    sprintByIssue.set(issue.id, parentSprint);
    changed++;
  }
  updated += changed;
  if (changed === 0) break;
}
console.log(`항목 ${items.length}개 확인, ${updated}개 ${DRY_RUN ? '맞출 예정' : '맞춤'}`);
