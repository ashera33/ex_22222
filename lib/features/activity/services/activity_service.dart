import 'dart:async';
import 'package:flutter/material.dart';
import '../../services/activity_service.dart';
import '../../services/voice_service.dart';
import 'fall_detected_modal.dart';

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
  StreamSubscription<bool>? _fallAlertSubscription;
  ActivityState _currentState = ActivityState.stationary;
  bool _isMonitoring = false;
  bool _fallModalOpen = false;

  @override
  void initState() {
    super.initState();
    _voiceService.initialize();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _fallAlertSubscription?.cancel();
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

    _subscription = _activityService.activityStream.listen((state) {
      setState(() => _currentState = state);

      _voiceService.announceActivity(state);
    });

    _fallAlertSubscription = _activityService.fallAlertStream.listen((
      alertaActiva,
    ) {
      if (alertaActiva && !_fallModalOpen) {
        _fallModalOpen = true;
        showFallDetectedModal(
          context,
          onConfirm: _activityService.confirmFallSafe,
        ).then((_) => _fallModalOpen = false);
      }
    });

    setState(() => _isMonitoring = true);
  }

  void _stopMonitoring() {
    _subscription?.cancel();
    _fallAlertSubscription?.cancel();
    _activityService.stop();
    _voiceService.stop();
    setState(() {
      _isMonitoring = false;
      _currentState = ActivityState.stationary;
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
      case ActivityState.fall:
        return Icons.warning_rounded;
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
        return '¡Caída!';
    }
  }
}