#!/system/bin/sh
# Boot warmup for InkPanel.
#
# Facts learned on this ROM (HITV205N / Vision 11):
#   - SystemUI binds a third-party TileService only if the owner app has been
#     started in the foreground at least once in this boot AND the user has
#     unlocked (CE storage available).
#   - Hisense MobileSafeService blocks startService even for root, so an
#     activity start is the only usable trigger.
#   - The tile is dropped during early boot if it cannot be resolved, so
#     QSTileHost must be forced to rebuild afterwards.

LOG=/data/local/tmp/inkpanel_boot.log
SPEC='custom(com.zuakeo.inkpanel/.PanelTile)'

(
  echo "=== boot $(date) ===" >> "$LOG"

  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 60 ]; do
    sleep 5
    i=$((i+1))
  done
  echo "boot_completed=$(getprop sys.boot_completed) wait=${i}" >> "$LOG"

  j=0
  while [ "$(getprop sys.user.0.ce_available)" != "true" ] && [ $j -lt 120 ]; do
    sleep 5
    j=$((j+1))
  done
  echo "unlocked=$(getprop sys.user.0.ce_available) wait=${j}" >> "$LOG"

  sleep 5
  am start -n com.zuakeo.inkpanel/.MainActivity --ez warm_only true >> "$LOG" 2>&1
  echo "warm rc=$?" >> "$LOG"
  sleep 8

  VAL=$(settings get secure sysui_qs_tiles)
  echo "tiles=$VAL" >> "$LOG"
  case "$VAL" in
    *"$SPEC"*)
      WITHOUT=$(echo "$VAL" | sed -e 's/custom(com\.zuakeo\.inkpanel\/\.PanelTile),//' -e 's/,custom(com\.zuakeo\.inkpanel\/\.PanelTile)//')
      settings put secure sysui_qs_tiles "$WITHOUT"
      sleep 1
      settings put secure sysui_qs_tiles "$VAL"
      echo "rebuilt" >> "$LOG"
      ;;
    *)
      echo "spec missing, skip rebuild" >> "$LOG"
      ;;
  esac

  sleep 5
  am start -n com.zuakeo.inkpanel/.MainActivity --ez warm_only true >> "$LOG" 2>&1
  echo "second warm rc=$?" >> "$LOG"
  echo "=== done $(date) ===" >> "$LOG"
) &
