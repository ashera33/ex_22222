import 'package:flutter/material.dart';

/// Modal que se muestra cuando se detecta una caída.
///
/// Úsalo llamando a [showFallDetectedModal] desde donde detectes
/// la caída (por ejemplo, escuchando fallAlertStream del ActivityService).
class FallDetectedModal extends StatelessWidget {
  final VoidCallback? onConfirm;

  const FallDetectedModal({super.key, this.onConfirm});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
      ),
      icon: const Icon(
        Icons.warning_amber_rounded,
        color: Colors.redAccent,
        size: 48,
      ),
      title: const Text(
        'Caída detectada',
        textAlign: TextAlign.center,
        style: TextStyle(fontWeight: FontWeight.bold),
      ),
      content: const Text(
        'Hemos detectado una posible caída. ¿Te encuentras bien?',
        textAlign: TextAlign.center,
      ),
      actionsAlignment: MainAxisAlignment.center,
      actions: [
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 12),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            onPressed: () {
              onConfirm?.call();
              Navigator.of(context).pop();
            },
            child: const Text('Estoy bien'),
          ),
        ),
      ],
    );
  }
}

/// Muestra el modal de caída detectada.
///
/// Ejemplo de uso desde activity_monitor_widget.dart, escuchando
/// el fallAlertStream del ActivityService:
/// ```dart
/// if (alertaActiva) {
///   showFallDetectedModal(
///     context,
///     onConfirm: _activityService.confirmFallSafe,
///   );
/// }
/// ```
Future<void> showFallDetectedModal(
  BuildContext context, {
  VoidCallback? onConfirm,
}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: false, // obliga a confirmar "Estoy bien"
    builder: (_) => FallDetectedModal(onConfirm: onConfirm),
  );
}