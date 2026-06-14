# Lições Aprendidas — CI/CD com EKS + Dynatrace

## 1. ImagePullBackOff — Caminho de imagem duplicado no ECR

**Problema:** O Kubernetes não conseguia baixar a imagem do ECR. O erro mostrava o nome do repositório duplicado:
```
835466267260.dkr.ecr.us-east-1.amazonaws.com/pedido-service/pedido-service:32c4421
```

**Causa:** O secret `ECR_REGISTRY` já incluía o nome do repositório (`/pedido-service`), e o `deployment.yaml` adicionava `/pedido-service` novamente.

**Correção** em `k8s/deployment.yaml`:
```yaml
# Errado
image: ${ECR}/pedido-service:${IMAGE_TAG}

# Correto
image: ${ECR}:${IMAGE_TAG}
```

---

## 2. kubectl apontando para localhost em vez do EKS

**Problema:**
```
Unable to connect to the server: dial tcp 127.0.0.1:50519: connectex: No connection could be made
```

**Causa:** O kubeconfig local estava apontando para um cluster local antigo (minikube/kind).

**Correção:**
```powershell
# Ver clusters disponíveis
aws eks list-clusters --region us-east-1

# Atualizar o kubeconfig para o cluster correto
aws eks update-kubeconfig --name <CLUSTER_NAME> --region us-east-1

# Confirmar o contexto ativo
kubectl config current-context
```

---

## 3. grep não existe no PowerShell

**Problema:**
```
grep : The term 'grep' is not recognized as the name of a cmdlet
```

**Causa:** `grep` é um comando Unix e não existe no PowerShell.

**Equivalente no PowerShell:**
```powershell
# Unix
kubectl describe pod -l app=pedido-service | grep -i dynatrace

# PowerShell (já é case-insensitive por padrão)
kubectl describe pod -l app=pedido-service | Select-String "dynatrace"
```

---

## 4. OneAgent não injetado — CSI Driver em Pending por falta de CPU

**Problema:** O init container do Dynatrace não aparecia nos pods, significando que o OneAgent não foi injetado.

**Diagnóstico:**
```powershell
# Verificar se o OneAgent foi injetado
kubectl get pod -l app=pedido-service -o jsonpath='{.items[0].spec.initContainers[*].name}'

# Ver status dos pods do Dynatrace
kubectl get pods -n dynatrace

# Ver o motivo do CSI driver estar Pending
kubectl describe pod dynatrace-oneagent-csi-driver-<ID> -n dynatrace
```

**Causa:** Nós com instância `t2.small` (1 vCPU) sem CPU disponível para o CSI driver do Dynatrace.

**Correção:** Criar node group com instâncias maiores:
```powershell
aws eks create-nodegroup `
  --cluster-name <CLUSTER_NAME> `
  --nodegroup-name nodegroup-t3-medium `
  --instance-types t3.medium `
  --ami-type AL2_x86_64 `
  --capacity-type ON_DEMAND `
  --scaling-config minSize=2,maxSize=4,desiredSize=2 `
  --node-role <NODE_ROLE_ARN> `
  --subnets <SUBNET_IDS> `
  --region us-east-1

# Drenar e remover node group antigo
kubectl drain <NODE_NAME> --ignore-daemonsets --delete-emptydir-data
aws eks delete-nodegroup --cluster-name <CLUSTER_NAME> --nodegroup-name <OLD_NODEGROUP> --region us-east-1
```

**Lição:** O mínimo recomendado pelo Dynatrace é `t3.medium` (2 vCPU / 4 GB) para rodar Operator + CSI Driver + aplicação.

---

## 5. DynaKube CR com apiUrl imutável

**Problema:**
```
The DynaKube's specification mutated the API URL although it is immutable.
Please delete the CR and then apply a new one
```

**Causa:** O campo `apiUrl` do DynaKube CR não pode ser alterado após a criação. Ao trocar de tenant do Dynatrace, a URL mudou e o Operator bloqueou a atualização.

**Correção:** Deletar o CR e deixar o pipeline recriar:
```powershell
kubectl delete dynakube dynakube -n dynatrace
```

---

## 6. Dynatrace Playground não suporta infraestrutura real

**Problema:** Os scopes `InstallerDownload` e `DataExport` não apareciam na criação de tokens.

**Causa:** O **Dynatrace Playground** é um ambiente demo com permissões limitadas — não permite conectar infraestrutura real como EKS.

**Correção:** Criar uma conta **trial gratuita** (15 dias) em `dynatrace.com/trial`. O trial dá acesso completo a todos os scopes necessários.

---

## 7. Personal Access Token vs API Token no Dynatrace

**Problema:** Mesmo após criar conta trial, os scopes `InstallerDownload` e `DataExport` não apareciam.

**Causa:** A criação estava sendo feita em **Personal Access Tokens**, que só têm scopes v2. O Dynatrace Operator precisa de **API Tokens** (scopes v1 + v2).

**Correção:** Acessar a URL correta para criação de API tokens:
```
https://<TENANT_ID>.live.dynatrace.com/ui/access-tokens
```

---

## 8. Scopes necessários para o Dynatrace Operator

### DT_API_TOKEN
| Scope | Finalidade |
|---|---|
| `InstallerDownload` | Download do OneAgent |
| `DataExport` | Exportação de dados de telemetria |
| `activeGateTokenManagement.read` | Gerenciamento do ActiveGate |
| `entities.read` | Leitura de entidades monitoradas |
| `settings.read` | Leitura de configurações (opcional, mas recomendado) |

### DT_DATA_INGEST_TOKEN
| Scope | Finalidade |
|---|---|
| `metrics.ingest` | Ingestão de métricas |
| `logs.ingest` | Ingestão de logs |
| `events.ingest` | Ingestão de eventos |

---

## 9. Fluxo completo de diagnóstico do Dynatrace no EKS

```powershell
# 1. Verificar se todos os pods do Dynatrace estão Running
kubectl get pods -n dynatrace

# 2. Verificar o status do DynaKube CR
kubectl get dynakube -n dynatrace
kubectl describe dynakube dynakube -n dynatrace

# 3. Verificar se o OneAgent foi injetado no pod da aplicação
kubectl get pod -l app=pedido-service -o jsonpath='{.items[0].spec.initContainers[*].name}'

# 4. Se não foi injetado, reiniciar o deployment
kubectl rollout restart deployment/pedido-service
kubectl rollout status deployment/pedido-service

# 5. Decodificar o token salvo no secret para validar
$encoded = kubectl get secret dynakube -n dynatrace -o jsonpath='{.data.apiToken}'
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($encoded))

# 6. Atualizar o secret manualmente se necessário
kubectl create secret generic dynakube `
  --from-literal=apiToken=<DT_API_TOKEN> `
  --from-literal=dataIngestToken=<DT_DATA_INGEST_TOKEN> `
  --namespace dynatrace `
  --dry-run=client -o yaml | kubectl apply -f -

# 7. Reiniciar o Operator para re-validar o token
kubectl rollout restart deployment/dynatrace-operator -n dynatrace
```

---

## 10. Verificar tipo de instância dos nós via AWS CLI

O comando `kubectl get nodes -o wide` **não mostra** o instance type. Use o AWS CLI:

```powershell
aws ec2 describe-instances `
  --filters "Name=private-dns-name,Values=<NODE_DNS_NAME>" `
  --query "Reservations[*].Instances[0].[PrivateDnsName,InstanceType]" `
  --region us-east-1 `
  --output json
```

---

## Resumo dos Status do DynaKube CR

| Status | Significado |
|---|---|
| `Error` | Problema de token, conexão ou configuração |
| `Deploying` | Operator validou o token e está provisionando |
| `Running` | Tudo funcionando, OneAgent sendo injetado |