# Откат версии

Репозиторий хранит полную историю. Перед публикацией создаётся baseline-коммит и тег `v1.3.5`.

## Посмотреть историю

```bash
git log --oneline --decorate --graph
```

## Откатить рабочую копию к тегу

Вариант 1. Временный просмотр без изменения ветки:

```bash
git checkout v1.3.5
```

Возврат к актуальной ветке:

```bash
git checkout main
```

Вариант 2. Полный откат ветки `main` к тегу (удаляет последующие коммиты из ветки):

```bash
git reset --hard v1.3.5
git push --force-with-lease origin main
```

Вариант 3. Безопасный откат через новый коммит, если ветка защищена:

```bash
git revert --no-commit v1.3.5..HEAD
git commit -m "Возврат к состоянию v1.3.5"
git push origin main
```

## Вернуться с удалённого репозитория на другом компьютере

```bash
git clone git@github.com:<owner>/<repo>.git
cd <repo>
git checkout v1.3.5
```

## Правила для будущих версий

1. Перед рискованными изменениями создавать тег: `git tag v1.3.6`.
2. Отправлять теги: `git push origin --tags`.
3. Не коммитить `Key/`, `local.properties`, `build/`, `.gradle/`, `.idea/`.
