# R8 для релизной сборки.
#
# Движок и горячий путь вызываются системой и через рефлексию из манифеста, поэтому
# точки входа сохраняются явно: иначе R8 вырежет сервис проверки звонков, и приложение
# получит роль, но не будет вызываться — отказ, который не виден до реального звонка.

-keep class com.mist3s.nopecall.core.NopeCallApp { *; }
-keep class com.mist3s.nopecall.core.screening.NopeCallScreeningService { *; }
-keep class com.mist3s.nopecall.core.boot.** { *; }

# Flutter добавляет свои правила сам; здесь только наше.
