import 'dart:async';
import 'package:flutter/material.dart';
import '../../services/activity_service.dart';
import '../../services/voice_service.dart';

/// Widget que monitorea actividad física en tiempo real
class ActivityMonitorWidget extends StatefulWidget {
  const ActivityMonitorWidget({super.key});

  @override
  State<ActivityMonitorWidget> createState() => _ActivityMonitorWidgetState();
}

class _ActivityMonitorWidgetState extends State<ActivityMonitorWidget> {
  final ActivityService _activityService = ActivityService();
  final VoiceService _voiceService = VoiceService();

  StreamSubscription<ActivityState>? _subscription;
  ActivityState _currentState = ActivityState.stationary;
  bool _isMonitoring = false;

  // Para el diálogo de caída
  Timer? _fallDialogTimer;
  bool _fallDialogVisible = false;

  @override
  void initState() {
    super.initState();
    _voiceService.initialize();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _fallDialogTimer?.cancel();
    _activityService.stop();
    _voiceService.dispose();
    super.dispose();
  }

  Future<void> _toggleMonitoring() async {
    if (_isMonitoring) {
      _stopMonitoring();
    } else {
      await _startMonitoring();
    }
  }

  Future<void> _startMonitoring() async {
    _activityService.start();

    _subscription = _activityService.stream.listen((state) {
      setState(() => _currentState = state);

      _voiceService.announceActivity(state);
    });

    setState(() => _isMonitoring = true);
  }

  void _stopMonitoring() {
    _subscription?.cancel();
    _activityService.stop();
    _voiceService.stop();
    _fallDialogTimer?.cancel();
    setState(() {
      _isMonitoring = false;
      _currentState = ActivityState.stationary;
    });
  }

  /// Maneja la detección de caída:
  /// 1. Anuncia por voz
  /// 2. Muestra diálogo
  /// 3. Si no responde en 15s, muestra mensaje secundario
  void _handleFall() {
    _voiceService.announceActivity(ActivityState.fall);

    if (_fallDialogVisible) return;
    _fallDialogVisible = true;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => _FallDialog(
        onConfirm: () {
          _fallDialogTimer?.cancel();
          _fallDialogVisible = false;
          Navigator.of(context).pop();
        },
      ),
    ).then((_) => _fallDialogVisible = false);

    // Timer de 15 segundos sin respuesta
    _fallDialogTimer = Timer(const Duration(seconds: 15), () {
      if (_fallDialogVisible && mounted) {
        _voiceService.announceActivity(ActivityState.fall);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            // Header
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text(
                  'Monitor de Actividad',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                ElevatedButton.icon(
                  onPressed: _toggleMonitoring,
                  icon: Icon(_isMonitoring ? Icons.stop : Icons.play_arrow),
                  label: Text(_isMonitoring ? 'Detener' : 'Iniciar'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _isMonitoring ? Colors.red : Colors.green,
                    foregroundColor: Colors.white,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Icono de estado
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 300),
              child: Icon(
                _getStateIcon(_currentState),
                key: ValueKey(_currentState),
                size: 80,
                color: _getStateColor(_currentState),
              ),
            ),

            const SizedBox(height: 16),

            // Texto de estado
            Text(
              _getStateLabel(_currentState),
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
                color: _getStateColor(_currentState),
              ),
            ),

            const SizedBox(height: 8),

            Text(
              _isMonitoring
                  ? 'Detectando actividad en tiempo real...'
                  : 'Presiona Iniciar para comenzar',
              style: TextStyle(color: Colors.grey[600], fontSize: 13),
            ),

            const SizedBox(height: 16),

            // Indicador de debounce
            if (_isMonitoring)
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: Colors.indigo.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Text(
                  '⏱ Debounce: 2s · Umbral caída: 25 m/s²',
                  style: TextStyle(fontSize: 11, color: Colors.indigo),
                ),
              ),
          ],
        ),
      ),
    );
  }

  IconData _getStateIcon(ActivityState state) {
    switch (state) {
      case ActivityState.walking:
        return Icons.directions_walk;
      case ActivityState.running:
        return Icons.directions_run;
      case ActivityState.stationary:
        return Icons.accessibility_new;
    }
  }

  Color _getStateColor(ActivityState state) {
    switch (state) {
      case ActivityState.walking:
        return Colors.blue;
      case ActivityState.running:
        return Colors.green;
      case ActivityState.stationary:
        return Colors.grey;
      case ActivityState.fall:
        return Colors.red;
    }
  }

  String _getStateLabel(ActivityState state) {
    switch (state) {
      case ActivityState.walking:
        return 'Caminando';
      case ActivityState.running:
        return 'Corriendo';
      case ActivityState.stationary:
        return 'Quieto';
      case ActivityState.fall:
        return '¡Caída detectada!';
    }
  }
}

/// Diálogo de caída con mensaje secundario a los 15 segundos
class _FallDialog extends StatefulWidget {
  final VoidCallback onConfirm;

  const _FallDialog({required this.onConfirm});

  @override
  State<_FallDialog> createState() => _FallDialogState();
}

class _FallDialogState extends State<_FallDialog> {
  bool _showSecondaryMessage = false;
  Timer? _timer;
  int _secondsLeft = 15;

  @override
  void initState() {
    super.initState();
    _startCountdown();
  }

  void _startCountdown() {
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        _secondsLeft--;
        if (_secondsLeft <= 0) {
          _showSecondaryMessage = true;
          timer.cancel();
        }
      });
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      title: const Row(
        children: [
          Icon(Icons.warning_rounded, color: Colors.red, size: 28),
          SizedBox(width: 8),
          Text('¡Caída detectada!'),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            '¿Estás bien?',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 12),
          if (!_showSecondaryMessage)
            Text(
              'Responde en $_secondsLeft segundos...',
              style: TextStyle(color: Colors.grey[600], fontSize: 13),
            ),
          if (_showSecondaryMessage) ...[
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.red.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Text(
                '⚠️ No hemos recibido respuesta. Por favor confirma que estás bien.',
                style: TextStyle(
                  color: Colors.red,
                  fontWeight: FontWeight.w500,
                ),
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ],
      ),
      actions: [
        ElevatedButton(
          onPressed: widget.onConfirm,
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.green,
            foregroundColor: Colors.white,
          ),
          child: const Text('Estoy bien'),
        ),
      ],
    );
  }
}
