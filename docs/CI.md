# Github CI 

## 打Tag

### 0) 确认版本与分支（当前 main、package.json = 17.0.3）

```
git switch main && git pull
node -p 'require("./package.json").version'
```

### 1) 在 main 的 commit 上打 tag（轻量或附注 tag 均可）

```
git tag v17.0.3                     
# 或：
git tag -a v0.7.3 -m "v0.7.3"
```

### 2) 推送 tag 触发 CI（本机若 22 端口被墙用第二行）

```
git push origin v0.7.3
git push ssh://git@ssh.github.com:443/yuchuangu85/AndroidPerformanceStudio.git v0.7.3
```

## 触发后会发生什么

查看

```
gh run list --workflow=electron.yml --limit 3
gh run watch <run-id> --exit-status
```

