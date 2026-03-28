/*
 * ble_trans_ae10_patch.c
 *
 * Option B firmware change — AC6328 is a pure BLE-UART bridge.
 * Android builds the complete TQ Bus frame; firmware forwards it verbatim.
 *
 * ONLY change needed in trans_att_write_callback() in ble_trans.c:
 * Replace the existing ae10 case with the one below.
 *
 * No torqeedo.c, no CRC, no escaping — deleted entirely.
 */

/* ── Drop-in replacement for the ae10 case ─────────────────────────────
   Find this in trans_att_write_callback():

   case ATT_CHARACTERISTIC_ae10_01_VALUE_HANDLE:
       tmp16 = sizeof(trans_test_read_write_buf);
       if ((offset >= tmp16) || (offset + buffer_size) > tmp16) {
           break;
       }
       memcpy(&trans_test_read_write_buf[offset], buffer, buffer_size);
       log_info("\n-ae10_rx(%d):", buffer_size);
       put_buf(buffer, buffer_size);
       break;

   Replace with:
   ──────────────────────────────────────────────────────────────────── */

case ATT_CHARACTERISTIC_ae10_01_VALUE_HANDLE:
    log_info("\n-ae10_tx(%d):", buffer_size);
    put_buf(buffer, buffer_size);
    uart_write((const char *)buffer, buffer_size);   /* forward frame to RS-485 */
    break;

/*
 * That's the entire firmware change.  Three lines.
 *
 * For STATUS replies coming back from the motor over UART RX:
 * Register your existing UART RX callback to forward bytes back over BLE:
 *
 *   In GATT_COMM_EVENT_CONNECTION_COMPLETE handler (already present):
 *
 *   uart_db_regiest_recieve_callback(trans_uart_rx_to_ble);
 *
 *   trans_uart_rx_to_ble() is already defined in ble_trans.c and forwards
 *   raw bytes to ATT_CHARACTERISTIC_ae02_01_VALUE_HANDLE notify — no change needed.
 *
 * RS-485 baud rate: set UART to 19200, 8N1 before calling ble_trans_init().
 */
